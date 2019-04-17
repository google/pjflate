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

import static org.junit.Assert.assertEquals;

import com.google.io.pjflate.Inflater.InflationException;
import com.google.io.pjflate.ZlibHeaderTrailerParser.ZlibHeader;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;
import java.util.zip.InflaterInputStream;
import org.junit.Before;
import org.junit.Test;

public class EndToEndInflateTest {
  private static final int SIZE = 20 * 1024 * 1024;
  private static final String TEST_FILE = "enwik8.zz";
  private Path testdataPath;

  @Before
  public void setUp() {
    testdataPath =
        Paths.get(System.getenv("TEST_SRCDIR"))
            .resolve("__main__/src/test/java/com/google/io/pjflate/testdata");
  }

  @Test
  public void test() throws Exception {
    testInternal("HeapBuffer", () -> ByteBuffer.allocate(SIZE));
  }

  @Test
  public void testDirectBufferCompatibility() throws Exception {
    testInternal("DirectBuffer", () -> ByteBuffer.allocateDirect(SIZE));
  }

  private void testInternal(String bufferType, Supplier<ByteBuffer> bufferSupplier)
      throws Exception {
    try (FileChannel ch = FileChannel.open(testdataPath.resolve(TEST_FILE))) {
      ByteBuffer inBuf = bufferSupplier.get();
      ByteBuffer outBuf = bufferSupplier.get();
      Inflater inflater = new Inflater();
      Adler32 hasher = new Adler32();

      Instant start = Instant.now();
      ch.read(inBuf);
      inBuf.flip();
      ZlibHeader header = ZlibHeaderTrailerParser.parseHeader(inBuf);
      if (header == null) {
        throw new AssertionError(
            "Expect to read the header in the first try since the buffer is large enough.");
      }
      if (header.dictChecksum != 0) {
        throw new AssertionError("Expect to have no dictionary.");
      }

      boolean done = false;
      while (!done) {
        switch (inflater.inflate(outBuf, inBuf)) {
          case NEED_MORE_INPUT:
            inBuf.compact();
            ch.read(inBuf);
            inBuf.flip();
            break;
          case NEED_MORE_OUTPUT:
            outBuf.flip();
            hasher.update(outBuf);
            outBuf.clear();
            break;
          case DONE:
            done = true;
            break;
        }
      }

      outBuf.flip();
      hasher.update(outBuf);
      outBuf.clear();

      inBuf.compact();
      ch.read(inBuf);
      inBuf.flip();

      long checksum = Objects.requireNonNull(ZlibHeaderTrailerParser.parseTrailer(inBuf));
      assertEquals(checksum, hasher.getValue());
      Instant end = Instant.now();

      System.err.printf(
          "Inflater(%s): %dms\n", bufferType, end.toEpochMilli() - start.toEpochMilli());
    }
  }

  @Test
  public void testJNI() throws Exception {
    java.util.zip.Inflater inf = new java.util.zip.Inflater();
    try (InflaterInputStream is =
        new InflaterInputStream(
            new BufferedInputStream(new FileInputStream(testdataPath.resolve(TEST_FILE).toFile())),
            inf,
            SIZE)) {
      byte[] buf = new byte[SIZE];

      Instant start = Instant.now();
      while (is.read(buf) != -1) {}
      Instant end = Instant.now();

      System.err.printf(
          "java.util.zip.InflaterStream: %dms\n", end.toEpochMilli() - start.toEpochMilli());
    }
  }

  @Test
  public void testJNI2() throws Exception {
    try (FileChannel ch = FileChannel.open(testdataPath.resolve(TEST_FILE))) {
      ByteBuffer inBuf = ByteBuffer.allocate(SIZE);
      ByteBuffer outBuf = ByteBuffer.allocate(SIZE);
      JDKInflater inflater = new JDKInflater();

      Instant start = Instant.now();
      ch.read(inBuf);
      inBuf.flip();
      boolean done = false;
      while (!done) {
        switch (inflater.inflate(outBuf, inBuf)) {
          case NEED_MORE_INPUT:
            inBuf.compact();
            ch.read(inBuf);
            inBuf.flip();
            break;
          case NEED_MORE_OUTPUT:
            outBuf.clear();
            break;
          case DONE:
            done = true;
            break;
        }
      }
      Instant end = Instant.now();

      System.err.printf(
          "java.util.zip.Inflater: %dms\n", end.toEpochMilli() - start.toEpochMilli());
    }
  }

  private static class JDKInflater {
    private final java.util.zip.Inflater inf = new java.util.zip.Inflater();

    Inflater.State inflate(ByteBuffer dst, ByteBuffer src) throws InflationException {
      while (true) {
        if (inf.finished()) {
          return Inflater.State.DONE;
        }
        if (!dst.hasRemaining()) {
          return Inflater.State.NEED_MORE_OUTPUT;
        }
        if (inf.needsInput()) {
          if (!src.hasRemaining()) {
            return Inflater.State.NEED_MORE_INPUT;
          }
          inf.setInput(src.array(), src.arrayOffset() + src.position(), src.remaining());
          src.position(src.position() + src.remaining());
        }
        try {
          int n = inf.inflate(dst.array(), dst.arrayOffset() + dst.position(), dst.remaining());
          dst.position(dst.position() + n);
        } catch (DataFormatException e) {
          throw new InflationException(e);
        }
      }
    }
  }
}
