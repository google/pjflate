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
import javax.annotation.Nullable;

class BitReader {
  /** Bits. {@code b & 0b11} takes the first two bits. */
  int b;
  /** Number of bits in {@code b}. */
  int nb;

  @Nullable private ByteBuffer src;

  void setByteBuffer(@Nullable ByteBuffer src) {
    this.src = src;
  }

  boolean readMore() {
    if (src == null || !src.hasRemaining()) {
      return false;
    }
    int c = src.get() & 0xFF;
    b = b & ((1 << nb) - 1);
    b |= (c << nb);
    nb += 8;
    return true;
  }
}
