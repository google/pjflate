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
import java.nio.ByteOrder;
import javax.annotation.Nullable;

/** Parse Zlib headers and trailers. */
public class ZlibHeaderTrailerParser {
  private static final int ZLIB_FIRST_HEADER_SIZE = 2;
  private static final int ZLIB_DICT_ID_SIZE = 4;
  private static final int ZLIB_TRAILER_SIZE = 4;
  private static final byte FLAG_BIT = 0b100000;

  private ZlibHeaderTrailerParser() {}

  @Nullable
  public static ZlibHeader parseHeader(ByteBuffer buf) {
    try (CommitGuard g = new CommitGuard(buf)) {
      if (buf.remaining() < ZLIB_FIRST_HEADER_SIZE) {
        return null;
      }

      byte cmf = buf.get();
      byte flags = buf.get();

      long dictId = 0;
      if ((flags & FLAG_BIT) != 0) {
        if (buf.remaining() < ZLIB_DICT_ID_SIZE) {
          return null;
        }
        ByteOrder originalOrder = buf.order();
        buf.order(ByteOrder.BIG_ENDIAN);
        dictId = buf.getInt();
        buf.order(originalOrder);
      }

      g.commit();
      return new ZlibHeader(cmf, flags, dictId);
    }
  }

  @Nullable
  public static Long parseTrailer(ByteBuffer buf) {
    if (buf.remaining() < ZLIB_TRAILER_SIZE) {
      return null;
    }

    ByteOrder originalOrder = buf.order();
    buf.order(ByteOrder.BIG_ENDIAN);
    long checksum = Integer.toUnsignedLong(buf.getInt());
    buf.order(originalOrder);

    return checksum;
  }

  public static class ZlibHeader {
    public final byte compressionMethod;

    public final byte flags;

    public long dictChecksum;

    public ZlibHeader(byte compressionMethod, byte flags, long dictChecksum) {
      this.compressionMethod = compressionMethod;
      this.flags = flags;
      this.dictChecksum = dictChecksum;
    }
  }
}
