// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.io.pjflate;

import java.nio.ByteBuffer;

/**
 * Output buffer for the inflation.
 *
 * <p>The deflate algorithm requires a look back while inflating. The look-back window is 2^15
 * bytes. This class is used to support this look-back copy operation.
 *
 * <p>This is a Java port of Go's dict_decoder
 * (https://golang.org/src/compress/flate/dict_decoder.go).
 */
class InflateOutputBuffer {
  private final byte[] history = new byte[1 << 15];
  private int writePos;
  private int readPos;
  private boolean full;

  void reset() {
    writePos = 0;
    readPos = 0;
    full = false;
  }

  int historySize() {
    if (full) {
      return history.length;
    }
    return writePos;
  }

  int readAvailable() {
    return writePos - readPos;
  }

  int writeAvailable() {
    return history.length - writePos;
  }

  void write(byte c) {
    history[writePos] = c;
    writePos++;
  }

  /**
   * Copies bytes from the history to the output buffer.
   *
   * <p>Returns the number of bytes copied. If the returned value is less than {@code length}, the
   * internal output buffer is too small.
   */
  int writeCopy(int dist, int length) {
    int dstBase = writePos;
    int dstPos = dstBase;
    int srcPos = dstPos - dist;
    int endPos = dstPos + length;
    if (endPos > history.length) {
      endPos = history.length;
    }

    if (srcPos < 0) {
      srcPos += history.length;
      int len = Math.min(history.length - srcPos, endPos - dstPos);
      System.arraycopy(history, srcPos, history, dstPos, len);
      dstPos += len;
      srcPos = 0;
    }

    while (dstPos < endPos) {
      int len = Math.min(endPos - dstPos, dstPos - srcPos);
      System.arraycopy(history, srcPos, history, dstPos, len);
      dstPos += len;
    }

    writePos = dstPos;
    return dstPos - dstBase;
  }

  /**
   * Write data to the buffered content from {@link ByteBuffer}.
   *
   * <p>Return the number of bytes written. If the returned value is less than {@code length}, the
   * internal output buffer is full.
   *
   * <p>{@code src.remaining()} should be larger than {@code length}.
   */
  int writeFrom(ByteBuffer src, int length) {
    try (CommitGuard g = new CommitGuard(src)) {
      int len = Math.min(length, writeAvailable());
      src.get(history, writePos, len);
      writePos += len;
      g.commit();
      return len;
    }
  }

  /**
   * Write the buffered content.
   *
   * <p>Return true if all contents are written. If return false, the destination buffer doesn't
   * have enough space.
   */
  boolean writeTo(ByteBuffer dst) {
    try (CommitGuard g = new CommitGuard(dst)) {
      int len = Math.min(readAvailable(), dst.remaining());
      dst.put(history, readPos, len);
      readPos += len;
      g.commit();

      if (readPos == writePos && writePos == history.length) {
        readPos = 0;
        writePos = 0;
        full = true;
      }
      return readAvailable() == 0;
    }
  }
}
