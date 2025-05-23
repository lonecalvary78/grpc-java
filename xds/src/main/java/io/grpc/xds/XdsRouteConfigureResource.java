/*
 * Copyright 2022 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.github.udpa.udpa.type.v1.TypedStruct;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedInteger;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig;
import io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy.RetryBackOff;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.ClusterSpecifierPlugin.NamedPluginConfig;
import io.grpc.xds.ClusterSpecifierPlugin.PluginConfig;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteAction.RetryPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.VirtualHost.Route.RouteMatch.PathMatcher;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.client.XdsClient.ResourceUpdate;
import io.grpc.xds.client.XdsResourceType;
import io.grpc.xds.internal.MatcherParser;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.Matchers.FractionMatcher;
import io.grpc.xds.internal.Matchers.HeaderMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

class XdsRouteConfigureResource extends XdsResourceType<RdsUpdate> {

  private static final String GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE =
      "GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE";
  @VisibleForTesting
  static boolean enableRouteLookup = GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_RLS_LB", true);

  static final String ADS_TYPE_URL_RDS =
      "type.googleapis.com/envoy.config.route.v3.RouteConfiguration";
  private static final String TYPE_URL_FILTER_CONFIG =
      "type.googleapis.com/envoy.config.route.v3.FilterConfig";
  @VisibleForTesting
  static final String HASH_POLICY_FILTER_STATE_KEY = "io.grpc.channel_id";
  // TODO(zdapeng): need to discuss how to handle unsupported values.
  private static final Set<Status.Code> SUPPORTED_RETRYABLE_CODES =
      Collections.unmodifiableSet(EnumSet.of(
          Status.Code.CANCELLED, Status.Code.DEADLINE_EXCEEDED, Status.Code.INTERNAL,
          Status.Code.RESOURCE_EXHAUSTED, Status.Code.UNAVAILABLE));

  private static final XdsRouteConfigureResource instance = new XdsRouteConfigureResource();

  static XdsRouteConfigureResource getInstance() {
    return instance;
  }

  @Override
  @Nullable
  protected String extractResourceName(Message unpackedResource) {
    if (!(unpackedResource instanceof RouteConfiguration)) {
      return null;
    }
    return ((RouteConfiguration) unpackedResource).getName();
  }

  @Override
  public String typeName() {
    return "RDS";
  }

  @Override
  public String typeUrl() {
    return ADS_TYPE_URL_RDS;
  }

  @Override
  public boolean shouldRetrieveResourceKeysForArgs() {
    return false;
  }

  @Override
  protected boolean isFullStateOfTheWorld() {
    return false;
  }

  @Override
  protected Class<RouteConfiguration> unpackedClassName() {
    return RouteConfiguration.class;
  }

  @Override
  protected RdsUpdate doParse(XdsResourceType.Args args, Message unpackedMessage)
      throws ResourceInvalidException {
    if (!(unpackedMessage instanceof RouteConfiguration)) {
      throw new ResourceInvalidException("Invalid message type: " + unpackedMessage.getClass());
    }
    return processRouteConfiguration(
        (RouteConfiguration) unpackedMessage, FilterRegistry.getDefaultRegistry(), args);
  }

  private static RdsUpdate processRouteConfiguration(
      RouteConfiguration routeConfig, FilterRegistry filterRegistry, XdsResourceType.Args args)
      throws ResourceInvalidException {
    return new RdsUpdate(extractVirtualHosts(routeConfig, filterRegistry, args));
  }

  static List<VirtualHost> extractVirtualHosts(
      RouteConfiguration routeConfig, FilterRegistry filterRegistry, XdsResourceType.Args args)
      throws ResourceInvalidException {
    Map<String, PluginConfig> pluginConfigMap = new HashMap<>();
    ImmutableSet.Builder<String> optionalPlugins = ImmutableSet.builder();

    if (enableRouteLookup) {
      List<ClusterSpecifierPlugin> plugins = routeConfig.getClusterSpecifierPluginsList();
      for (ClusterSpecifierPlugin plugin : plugins) {
        String pluginName = plugin.getExtension().getName();
        PluginConfig pluginConfig = parseClusterSpecifierPlugin(plugin);
        if (pluginConfig != null) {
          if (pluginConfigMap.put(pluginName, pluginConfig) != null) {
            throw new ResourceInvalidException(
                "Multiple ClusterSpecifierPlugins with the same name: " + pluginName);
          }
        } else {
          // The plugin parsed successfully, and it's not supported, but it's marked as optional.
          optionalPlugins.add(pluginName);
        }
      }
    }
    List<VirtualHost> virtualHosts = new ArrayList<>(routeConfig.getVirtualHostsCount());
    for (io.envoyproxy.envoy.config.route.v3.VirtualHost virtualHostProto
        : routeConfig.getVirtualHostsList()) {
      StructOrError<VirtualHost> virtualHost =
          parseVirtualHost(virtualHostProto, filterRegistry, pluginConfigMap,
              optionalPlugins.build(), args);
      if (virtualHost.getErrorDetail() != null) {
        throw new ResourceInvalidException(
            "RouteConfiguration contains invalid virtual host: " + virtualHost.getErrorDetail());
      }
      virtualHosts.add(virtualHost.getStruct());
    }
    return virtualHosts;
  }

  private static StructOrError<VirtualHost> parseVirtualHost(
      io.envoyproxy.envoy.config.route.v3.VirtualHost proto, FilterRegistry filterRegistry,
       Map<String, PluginConfig> pluginConfigMap,
      Set<String> optionalPlugins, XdsResourceType.Args args) {
    String name = proto.getName();
    List<Route> routes = new ArrayList<>(proto.getRoutesCount());
    for (io.envoyproxy.envoy.config.route.v3.Route routeProto : proto.getRoutesList()) {
      StructOrError<Route> route = parseRoute(
          routeProto, filterRegistry, pluginConfigMap, optionalPlugins, args);
      if (route == null) {
        continue;
      }
      if (route.getErrorDetail() != null) {
        return StructOrError.fromError(
            "Virtual host [" + name + "] contains invalid route : " + route.getErrorDetail());
      }
      routes.add(route.getStruct());
    }
    StructOrError<Map<String, Filter.FilterConfig>> overrideConfigs =
        parseOverrideFilterConfigs(proto.getTypedPerFilterConfigMap(), filterRegistry);
    if (overrideConfigs.getErrorDetail() != null) {
      return StructOrError.fromError(
          "VirtualHost [" + proto.getName() + "] contains invalid HttpFilter config: "
              + overrideConfigs.getErrorDetail());
    }
    return StructOrError.fromStruct(VirtualHost.create(
        name, proto.getDomainsList(), routes, overrideConfigs.getStruct()));
  }

  @VisibleForTesting
  static StructOrError<Map<String, FilterConfig>> parseOverrideFilterConfigs(
      Map<String, Any> rawFilterConfigMap, FilterRegistry filterRegistry) {
    Map<String, FilterConfig> overrideConfigs = new HashMap<>();
    for (String name : rawFilterConfigMap.keySet()) {
      Any anyConfig = rawFilterConfigMap.get(name);
      String typeUrl = anyConfig.getTypeUrl();
      boolean isOptional = false;
      if (typeUrl.equals(TYPE_URL_FILTER_CONFIG)) {
        io.envoyproxy.envoy.config.route.v3.FilterConfig filterConfig;
        try {
          filterConfig =
              anyConfig.unpack(io.envoyproxy.envoy.config.route.v3.FilterConfig.class);
        } catch (InvalidProtocolBufferException e) {
          return StructOrError.fromError(
              "FilterConfig [" + name + "] contains invalid proto: " + e);
        }
        isOptional = filterConfig.getIsOptional();
        anyConfig = filterConfig.getConfig();
        typeUrl = anyConfig.getTypeUrl();
      }
      Message rawConfig = anyConfig;
      try {
        if (typeUrl.equals(TYPE_URL_TYPED_STRUCT_UDPA)) {
          TypedStruct typedStruct = anyConfig.unpack(TypedStruct.class);
          typeUrl = typedStruct.getTypeUrl();
          rawConfig = typedStruct.getValue();
        } else if (typeUrl.equals(TYPE_URL_TYPED_STRUCT)) {
          com.github.xds.type.v3.TypedStruct newTypedStruct =
              anyConfig.unpack(com.github.xds.type.v3.TypedStruct.class);
          typeUrl = newTypedStruct.getTypeUrl();
          rawConfig = newTypedStruct.getValue();
        }
      } catch (InvalidProtocolBufferException e) {
        return StructOrError.fromError(
            "FilterConfig [" + name + "] contains invalid proto: " + e);
      }
      Filter.Provider provider = filterRegistry.get(typeUrl);
      if (provider == null) {
        if (isOptional) {
          continue;
        }
        return StructOrError.fromError(
            "HttpFilter [" + name + "](" + typeUrl + ") is required but unsupported");
      }
      ConfigOrError<? extends Filter.FilterConfig> filterConfig =
          provider.parseFilterConfigOverride(rawConfig);
      if (filterConfig.errorDetail != null) {
        return StructOrError.fromError(
            "Invalid filter config for HttpFilter [" + name + "]: " + filterConfig.errorDetail);
      }
      overrideConfigs.put(name, filterConfig.config);
    }
    return StructOrError.fromStruct(overrideConfigs);
  }

  @VisibleForTesting
  @Nullable
  static StructOrError<Route> parseRoute(
      io.envoyproxy.envoy.config.route.v3.Route proto, FilterRegistry filterRegistry,
      Map<String, PluginConfig> pluginConfigMap,
      Set<String> optionalPlugins, XdsResourceType.Args args) {
    StructOrError<RouteMatch> routeMatch = parseRouteMatch(proto.getMatch());
    if (routeMatch == null) {
      return null;
    }
    if (routeMatch.getErrorDetail() != null) {
      return StructOrError.fromError(
          "Route [" + proto.getName() + "] contains invalid RouteMatch: "
              + routeMatch.getErrorDetail());
    }

    StructOrError<Map<String, FilterConfig>> overrideConfigsOrError =
        parseOverrideFilterConfigs(proto.getTypedPerFilterConfigMap(), filterRegistry);
    if (overrideConfigsOrError.getErrorDetail() != null) {
      return StructOrError.fromError(
          "Route [" + proto.getName() + "] contains invalid HttpFilter config: "
              + overrideConfigsOrError.getErrorDetail());
    }
    Map<String, FilterConfig> overrideConfigs = overrideConfigsOrError.getStruct();

    switch (proto.getActionCase()) {
      case ROUTE:
        StructOrError<RouteAction> routeAction =
            parseRouteAction(proto.getRoute(), filterRegistry, pluginConfigMap,
                optionalPlugins, args);
        if (routeAction == null) {
          return null;
        }
        if (routeAction.getErrorDetail() != null) {
          return StructOrError.fromError(
              "Route [" + proto.getName() + "] contains invalid RouteAction: "
                  + routeAction.getErrorDetail());
        }
        return StructOrError.fromStruct(
            Route.forAction(routeMatch.getStruct(), routeAction.getStruct(), overrideConfigs));
      case NON_FORWARDING_ACTION:
        return StructOrError.fromStruct(
            Route.forNonForwardingAction(routeMatch.getStruct(), overrideConfigs));
      case REDIRECT:
      case DIRECT_RESPONSE:
      case FILTER_ACTION:
      case ACTION_NOT_SET:
      default:
        return StructOrError.fromError(
            "Route [" + proto.getName() + "] with unknown action type: " + proto.getActionCase());
    }
  }

  @VisibleForTesting
  @Nullable
  static StructOrError<RouteMatch> parseRouteMatch(
      io.envoyproxy.envoy.config.route.v3.RouteMatch proto) {
    if (proto.getQueryParametersCount() != 0) {
      return null;
    }
    StructOrError<PathMatcher> pathMatch = parsePathMatcher(proto);
    if (pathMatch.getErrorDetail() != null) {
      return StructOrError.fromError(pathMatch.getErrorDetail());
    }

    FractionMatcher fractionMatch = null;
    if (proto.hasRuntimeFraction()) {
      StructOrError<FractionMatcher> parsedFraction =
          parseFractionMatcher(proto.getRuntimeFraction().getDefaultValue());
      if (parsedFraction.getErrorDetail() != null) {
        return StructOrError.fromError(parsedFraction.getErrorDetail());
      }
      fractionMatch = parsedFraction.getStruct();
    }

    List<HeaderMatcher> headerMatchers = new ArrayList<>();
    for (io.envoyproxy.envoy.config.route.v3.HeaderMatcher hmProto : proto.getHeadersList()) {
      StructOrError<HeaderMatcher> headerMatcher = parseHeaderMatcher(hmProto);
      if (headerMatcher.getErrorDetail() != null) {
        return StructOrError.fromError(headerMatcher.getErrorDetail());
      }
      headerMatchers.add(headerMatcher.getStruct());
    }

    return StructOrError.fromStruct(RouteMatch.create(
        pathMatch.getStruct(), headerMatchers, fractionMatch));
  }

  @VisibleForTesting
  static StructOrError<PathMatcher> parsePathMatcher(
      io.envoyproxy.envoy.config.route.v3.RouteMatch proto) {
    boolean caseSensitive = proto.getCaseSensitive().getValue();
    switch (proto.getPathSpecifierCase()) {
      case PREFIX:
        return StructOrError.fromStruct(
            PathMatcher.fromPrefix(proto.getPrefix(), caseSensitive));
      case PATH:
        return StructOrError.fromStruct(PathMatcher.fromPath(proto.getPath(), caseSensitive));
      case SAFE_REGEX:
        String rawPattern = proto.getSafeRegex().getRegex();
        Pattern safeRegEx;
        try {
          safeRegEx = Pattern.compile(rawPattern);
        } catch (PatternSyntaxException e) {
          return StructOrError.fromError("Malformed safe regex pattern: " + e.getMessage());
        }
        return StructOrError.fromStruct(PathMatcher.fromRegEx(safeRegEx));
      case PATHSPECIFIER_NOT_SET:
      default:
        return StructOrError.fromError("Unknown path match type");
    }
  }

  private static StructOrError<FractionMatcher> parseFractionMatcher(FractionalPercent proto) {
    int numerator = proto.getNumerator();
    int denominator = 0;
    switch (proto.getDenominator()) {
      case HUNDRED:
        denominator = 100;
        break;
      case TEN_THOUSAND:
        denominator = 10_000;
        break;
      case MILLION:
        denominator = 1_000_000;
        break;
      case UNRECOGNIZED:
      default:
        return StructOrError.fromError(
            "Unrecognized fractional percent denominator: " + proto.getDenominator());
    }
    return StructOrError.fromStruct(FractionMatcher.create(numerator, denominator));
  }

  @VisibleForTesting
  static StructOrError<HeaderMatcher> parseHeaderMatcher(
      io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto) {
    try {
      Matchers.HeaderMatcher headerMatcher = MatcherParser.parseHeaderMatcher(proto);
      return StructOrError.fromStruct(headerMatcher);
    } catch (IllegalArgumentException e) {
      return StructOrError.fromError(e.getMessage());
    }
  }

  /**
   * Parses the RouteAction config. The returned result may contain a (parsed form)
   * {@link RouteAction} or an error message. Returns {@code null} if the RouteAction
   * should be ignored.
   */
  @VisibleForTesting
  @Nullable
  static StructOrError<RouteAction> parseRouteAction(
      io.envoyproxy.envoy.config.route.v3.RouteAction proto, FilterRegistry filterRegistry,
      Map<String, PluginConfig> pluginConfigMap,
      Set<String> optionalPlugins, XdsResourceType.Args args) {
    Long timeoutNano = null;
    if (proto.hasMaxStreamDuration()) {
      io.envoyproxy.envoy.config.route.v3.RouteAction.MaxStreamDuration maxStreamDuration
          = proto.getMaxStreamDuration();
      if (maxStreamDuration.hasGrpcTimeoutHeaderMax()) {
        timeoutNano = Durations.toNanos(maxStreamDuration.getGrpcTimeoutHeaderMax());
      } else if (maxStreamDuration.hasMaxStreamDuration()) {
        timeoutNano = Durations.toNanos(maxStreamDuration.getMaxStreamDuration());
      }
    }
    RetryPolicy retryPolicy = null;
    if (proto.hasRetryPolicy()) {
      StructOrError<RetryPolicy> retryPolicyOrError = parseRetryPolicy(proto.getRetryPolicy());
      if (retryPolicyOrError != null) {
        if (retryPolicyOrError.getErrorDetail() != null) {
          return StructOrError.fromError(retryPolicyOrError.getErrorDetail());
        }
        retryPolicy = retryPolicyOrError.getStruct();
      }
    }
    List<HashPolicy> hashPolicies = new ArrayList<>();
    for (io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy config
        : proto.getHashPolicyList()) {
      HashPolicy policy = null;
      boolean terminal = config.getTerminal();
      switch (config.getPolicySpecifierCase()) {
        case HEADER:
          io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.Header headerCfg =
              config.getHeader();
          Pattern regEx = null;
          String regExSubstitute = null;
          if (headerCfg.hasRegexRewrite() && headerCfg.getRegexRewrite().hasPattern()) {
            regEx = Pattern.compile(headerCfg.getRegexRewrite().getPattern().getRegex());
            regExSubstitute = headerCfg.getRegexRewrite().getSubstitution();
          }
          policy = HashPolicy.forHeader(
              terminal, headerCfg.getHeaderName(), regEx, regExSubstitute);
          break;
        case FILTER_STATE:
          if (config.getFilterState().getKey().equals(HASH_POLICY_FILTER_STATE_KEY)) {
            policy = HashPolicy.forChannelId(terminal);
          }
          break;
        default:
          // Ignore
      }
      if (policy != null) {
        hashPolicies.add(policy);
      }
    }

    switch (proto.getClusterSpecifierCase()) {
      case CLUSTER:
        return StructOrError.fromStruct(RouteAction.forCluster(
            proto.getCluster(), hashPolicies, timeoutNano, retryPolicy,
            GrpcUtil.getFlag(GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE, false)
            && args.getServerInfo().isTrustedXdsServer() && proto.getAutoHostRewrite().getValue()));
      case CLUSTER_HEADER:
        return null;
      case WEIGHTED_CLUSTERS:
        List<io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight> clusterWeights
            = proto.getWeightedClusters().getClustersList();
        if (clusterWeights.isEmpty()) {
          return StructOrError.fromError("No cluster found in weighted cluster list");
        }
        List<ClusterWeight> weightedClusters = new ArrayList<>();
        long clusterWeightSum = 0;
        for (io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight clusterWeight
            : clusterWeights) {
          StructOrError<ClusterWeight> clusterWeightOrError =
              parseClusterWeight(clusterWeight, filterRegistry);
          if (clusterWeightOrError.getErrorDetail() != null) {
            return StructOrError.fromError("RouteAction contains invalid ClusterWeight: "
                + clusterWeightOrError.getErrorDetail());
          }
          ClusterWeight parsedWeight = clusterWeightOrError.getStruct();
          clusterWeightSum += parsedWeight.weight();
          weightedClusters.add(parsedWeight);
        }
        if (clusterWeightSum <= 0) {
          return StructOrError.fromError("Sum of cluster weights should be above 0.");
        }
        if (clusterWeightSum > UnsignedInteger.MAX_VALUE.longValue()) {
          return StructOrError.fromError(String.format(
              "Sum of cluster weights should be less than the maximum unsigned integer (%d), but"
                  + " was %d. ",
              UnsignedInteger.MAX_VALUE.longValue(), clusterWeightSum));
        }
        return StructOrError.fromStruct(VirtualHost.Route.RouteAction.forWeightedClusters(
            weightedClusters, hashPolicies, timeoutNano, retryPolicy,
            GrpcUtil.getFlag(GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE, false)
            && args.getServerInfo().isTrustedXdsServer() && proto.getAutoHostRewrite().getValue()));
      case CLUSTER_SPECIFIER_PLUGIN:
        if (enableRouteLookup) {
          String pluginName = proto.getClusterSpecifierPlugin();
          PluginConfig pluginConfig = pluginConfigMap.get(pluginName);
          if (pluginConfig == null) {
            // Skip route if the plugin is not registered, but it is optional.
            if (optionalPlugins.contains(pluginName)) {
              return null;
            }
            return StructOrError.fromError(
                "ClusterSpecifierPlugin for [" + pluginName + "] not found");
          }
          NamedPluginConfig namedPluginConfig = NamedPluginConfig.create(pluginName, pluginConfig);
          return StructOrError.fromStruct(VirtualHost.Route.RouteAction.forClusterSpecifierPlugin(
              namedPluginConfig, hashPolicies, timeoutNano, retryPolicy,
              GrpcUtil.getFlag(GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE, false)
              && args.getServerInfo().isTrustedXdsServer()
                  && proto.getAutoHostRewrite().getValue()));
        } else {
          return null;
        }
      case CLUSTERSPECIFIER_NOT_SET:
      default:
        return null;
    }
  }

  @Nullable // Return null if we ignore the given policy.
  private static StructOrError<VirtualHost.Route.RouteAction.RetryPolicy> parseRetryPolicy(
      io.envoyproxy.envoy.config.route.v3.RetryPolicy retryPolicyProto) {
    int maxAttempts = 2;
    if (retryPolicyProto.hasNumRetries()) {
      maxAttempts = retryPolicyProto.getNumRetries().getValue() + 1;
    }
    Duration initialBackoff = Durations.fromMillis(25);
    Duration maxBackoff = Durations.fromMillis(250);
    if (retryPolicyProto.hasRetryBackOff()) {
      RetryBackOff retryBackOff = retryPolicyProto.getRetryBackOff();
      if (!retryBackOff.hasBaseInterval()) {
        return StructOrError.fromError("No base_interval specified in retry_backoff");
      }
      Duration originalInitialBackoff = initialBackoff = retryBackOff.getBaseInterval();
      if (Durations.compare(initialBackoff, Durations.ZERO) <= 0) {
        return StructOrError.fromError("base_interval in retry_backoff must be positive");
      }
      if (Durations.compare(initialBackoff, Durations.fromMillis(1)) < 0) {
        initialBackoff = Durations.fromMillis(1);
      }
      if (retryBackOff.hasMaxInterval()) {
        maxBackoff = retryPolicyProto.getRetryBackOff().getMaxInterval();
        if (Durations.compare(maxBackoff, originalInitialBackoff) < 0) {
          return StructOrError.fromError(
              "max_interval in retry_backoff cannot be less than base_interval");
        }
        if (Durations.compare(maxBackoff, Durations.fromMillis(1)) < 0) {
          maxBackoff = Durations.fromMillis(1);
        }
      } else {
        maxBackoff = Durations.fromNanos(Durations.toNanos(initialBackoff) * 10);
      }
    }
    Iterable<String> retryOns =
        Splitter.on(',').omitEmptyStrings().trimResults().split(retryPolicyProto.getRetryOn());
    ImmutableList.Builder<Status.Code> retryableStatusCodesBuilder = ImmutableList.builder();
    for (String retryOn : retryOns) {
      Status.Code code;
      try {
        code = Status.Code.valueOf(retryOn.toUpperCase(Locale.US).replace('-', '_'));
      } catch (IllegalArgumentException e) {
        // unsupported value, such as "5xx"
        continue;
      }
      if (!SUPPORTED_RETRYABLE_CODES.contains(code)) {
        // unsupported value
        continue;
      }
      retryableStatusCodesBuilder.add(code);
    }
    List<Status.Code> retryableStatusCodes = retryableStatusCodesBuilder.build();
    return StructOrError.fromStruct(
        VirtualHost.Route.RouteAction.RetryPolicy.create(
            maxAttempts, retryableStatusCodes, initialBackoff, maxBackoff,
            /* perAttemptRecvTimeout= */ null));
  }

  @VisibleForTesting
  static StructOrError<VirtualHost.Route.RouteAction.ClusterWeight> parseClusterWeight(
      io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight proto,
      FilterRegistry filterRegistry) {
    StructOrError<Map<String, Filter.FilterConfig>> overrideConfigs =
        parseOverrideFilterConfigs(proto.getTypedPerFilterConfigMap(), filterRegistry);
    if (overrideConfigs.getErrorDetail() != null) {
      return StructOrError.fromError(
          "ClusterWeight [" + proto.getName() + "] contains invalid HttpFilter config: "
              + overrideConfigs.getErrorDetail());
    }
    return StructOrError.fromStruct(VirtualHost.Route.RouteAction.ClusterWeight.create(
        proto.getName(),
        Integer.toUnsignedLong(proto.getWeight().getValue()),
        overrideConfigs.getStruct()));
  }

  @Nullable // null if the plugin is not supported, but it's marked as optional.
  private static PluginConfig parseClusterSpecifierPlugin(ClusterSpecifierPlugin pluginProto)
      throws ResourceInvalidException {
    return parseClusterSpecifierPlugin(
        pluginProto, ClusterSpecifierPluginRegistry.getDefaultRegistry());
  }

  @Nullable // null if the plugin is not supported, but it's marked as optional.
  @VisibleForTesting
  static PluginConfig parseClusterSpecifierPlugin(
      ClusterSpecifierPlugin pluginProto, ClusterSpecifierPluginRegistry registry)
      throws ResourceInvalidException {
    TypedExtensionConfig extension = pluginProto.getExtension();
    String pluginName = extension.getName();
    Any anyConfig = extension.getTypedConfig();
    String typeUrl = anyConfig.getTypeUrl();
    Message rawConfig = anyConfig;
    if (typeUrl.equals(TYPE_URL_TYPED_STRUCT_UDPA) || typeUrl.equals(TYPE_URL_TYPED_STRUCT)) {
      try {
        TypedStruct typedStruct = unpackCompatibleType(
            anyConfig, TypedStruct.class, TYPE_URL_TYPED_STRUCT_UDPA, TYPE_URL_TYPED_STRUCT);
        typeUrl = typedStruct.getTypeUrl();
        rawConfig = typedStruct.getValue();
      } catch (InvalidProtocolBufferException e) {
        throw new ResourceInvalidException(
            "ClusterSpecifierPlugin [" + pluginName + "] contains invalid proto", e);
      }
    }
    io.grpc.xds.ClusterSpecifierPlugin plugin = registry.get(typeUrl);
    if (plugin == null) {
      if (!pluginProto.getIsOptional()) {
        throw new ResourceInvalidException("Unsupported ClusterSpecifierPlugin type: " + typeUrl);
      }
      return null;
    }
    ConfigOrError<? extends PluginConfig> pluginConfigOrError = plugin.parsePlugin(rawConfig);
    if (pluginConfigOrError.errorDetail != null) {
      throw new ResourceInvalidException(pluginConfigOrError.errorDetail);
    }
    return pluginConfigOrError.config;
  }

  static final class RdsUpdate implements ResourceUpdate {
    // The list virtual hosts that make up the route table.
    final List<VirtualHost> virtualHosts;

    RdsUpdate(List<VirtualHost> virtualHosts) {
      this.virtualHosts = Collections.unmodifiableList(
          new ArrayList<>(checkNotNull(virtualHosts, "virtualHosts")));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("virtualHosts", virtualHosts)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(virtualHosts);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RdsUpdate that = (RdsUpdate) o;
      return Objects.equals(virtualHosts, that.virtualHosts);
    }
  }
}
