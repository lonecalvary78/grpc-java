/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Abstract base class for tests of {@link ReadableBuffer} subclasses.
 */
@RunWith(JUnit4.class)
public abstract class ReadableBufferTestBase {
  // Use a long string to ensure that any chunking/splitting works correctly.
  protected static final String msg = repeatUntilLength("hello", 8 * 1024);

  @Test
  public void bufferShouldReadAllBytes() {
    ReadableBuffer buffer = buffer();
    for (int ix = 0; ix < msg.length(); ++ix) {
      assertEquals(msg.length() - ix, buffer.readableBytes());
      assertEquals(msg.charAt(ix), buffer.readUnsignedByte());
    }
    assertEquals(0, buffer.readableBytes());
  }

  @Test
  public void readToArrayShouldSucceed() {
    ReadableBuffer buffer = buffer();
    byte[] array = new byte[msg.length()];
    buffer.readBytes(array, 0, array.length);
    assertArrayEquals(msg.getBytes(UTF_8), array);
    assertEquals(0, buffer.readableBytes());
  }

  @Test
  public void partialReadToArrayShouldSucceed() {
    ReadableBuffer buffer = buffer();
    byte[] array = new byte[msg.length()];
    buffer.readBytes(array, 1, 2);
    assertArrayEquals(new byte[] {'h', 'e'}, Arrays.copyOfRange(array, 1, 3));
    assertEquals(msg.length() - 2, buffer.readableBytes());
  }

  @Test
  public void readToStreamShouldSucceed() throws Exception {
    ReadableBuffer buffer = buffer();
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    buffer.readBytes(stream, msg.length());
    assertArrayEquals(msg.getBytes(UTF_8), stream.toByteArray());
    assertEquals(0, buffer.readableBytes());
  }

  @Test
  public void partialReadToStreamShouldSucceed() throws Exception {
    ReadableBuffer buffer = buffer();
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    buffer.readBytes(stream, 2);
    assertArrayEquals(new byte[]{'h', 'e'}, Arrays.copyOfRange(stream.toByteArray(), 0, 2));
    assertEquals(msg.length() - 2, buffer.readableBytes());
  }

  @Test
  public void readToByteBufferShouldSucceed() {
    ReadableBuffer buffer = buffer();
    ByteBuffer byteBuffer = ByteBuffer.allocate(msg.length());
    buffer.readBytes(byteBuffer);
    ((Buffer) byteBuffer).flip();
    byte[] array = new byte[msg.length()];
    byteBuffer.get(array);
    assertArrayEquals(msg.getBytes(UTF_8), array);
    assertEquals(0, buffer.readableBytes());
  }

  @Test
  public void partialReadToByteBufferShouldSucceed() {
    ReadableBuffer buffer = buffer();
    ByteBuffer byteBuffer = ByteBuffer.allocate(2);
    buffer.readBytes(byteBuffer);
    ((Buffer) byteBuffer).flip();
    byte[] array = new byte[2];
    byteBuffer.get(array);
    assertArrayEquals(new byte[]{'h', 'e'}, array);
    assertEquals(msg.length() - 2, buffer.readableBytes());
  }

  @Test
  public void partialReadToReadableBufferShouldSucceed() {
    ReadableBuffer buffer = buffer();
    ReadableBuffer newBuffer = buffer.readBytes(2);
    assertEquals(2, newBuffer.readableBytes());
    assertEquals(msg.length() - 2, buffer.readableBytes());
    byte[] array = new byte[2];
    newBuffer.readBytes(array, 0, 2);
    assertArrayEquals(new byte[] {'h', 'e'}, Arrays.copyOfRange(array, 0, 2));     
  }

  @Test
  public void markAndResetWithReadShouldSucceed() {
    ReadableBuffer buffer = buffer();
    int offset = 5;
    buffer.readBytes(new byte[offset], 0, offset);
    buffer.mark();
    int b = buffer.readUnsignedByte();
    assertEquals(msg.length() - offset - 1, buffer.readableBytes());
    buffer.reset();
    assertEquals(msg.length() - offset, buffer.readableBytes());
    assertEquals(b, buffer.readUnsignedByte());
  }

  @Test
  public void markAndResetWithReadToReadableBufferShouldSucceed() {
    ReadableBuffer buffer = buffer();
    int offset = 5;
    buffer.readBytes(offset);
    int testLen = 100;
    buffer.mark();
    ReadableBuffer first = buffer.readBytes(testLen);
    assertEquals(msg.length() - offset - testLen, buffer.readableBytes());
    buffer.reset();
    assertEquals(msg.length() - offset, buffer.readableBytes());
    ReadableBuffer second = buffer.readBytes(testLen);
    byte[] array1 = new byte[testLen];
    byte[] array2 = new byte[testLen];
    first.readBytes(array1, 0, testLen);
    second.readBytes(array2, 0, testLen);
    assertArrayEquals(array1, array2);
  }

  @Test
  public void getByteBufferDoesNotAffectBufferPosition() {
    ReadableBuffer buffer = buffer();
    Assume.assumeTrue(buffer.byteBufferSupported());
    ByteBuffer byteBuffer = buffer.getByteBuffer();
    assertEquals(msg.length(), buffer.readableBytes());
    byteBuffer.get(new byte[byteBuffer.remaining()]);
    assertEquals(msg.length(), buffer.readableBytes());
  }

  @Test
  public void getByteBufferIsNotAffectedByBufferRead() {
    ReadableBuffer buffer = buffer();
    Assume.assumeTrue(buffer.byteBufferSupported());
    ByteBuffer byteBuffer = buffer.getByteBuffer();
    int initialRemaining = byteBuffer.remaining();
    buffer.readBytes(new byte[100], 0, 100);
    assertEquals(initialRemaining, byteBuffer.remaining());
  }

  protected abstract ReadableBuffer buffer();

  private static String repeatUntilLength(String toRepeat, int length) {
    StringBuilder buf = new StringBuilder();
    while (buf.length() < length) {
      buf.append(toRepeat);
    }
    buf.setLength(length);
    return buf.toString();
  }
}
