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

import javax.annotation.Nullable;

/**
 * Index of the Huffman code.
 *
 * <p>The code is a pure Java port of Go's flate code
 * (https://golang.org/src/compress/flate/inflate.go).
 *
 * <p>It calculates the Huffman tree as instructed in RFC 1951, and then it constructs a prefix to
 * symbol mapping as described in https://github.com/madler/zlib/raw/master/doc/algorithm.txt.
 */
class HuffmanTreeIndex {
  private static final boolean ENABLE_DEBUG = false;
  // Max length of Huffman code
  private static final int MAX_CODE_LEN = 16;
  // Threshold on the Huffman code length for chunking. The code below this length can be looked
  // up in one shot. The code above this length needs two look-ups.
  private static final int CHUNKING_THRESHOLD = 9;
  private static final int CHUNK_SIZE = 1 << CHUNKING_THRESHOLD;
  // Mask and shift amount for chunk values. `VAL & CHUNK_COUNT_MASK` is a length of the code.
  // `VAL >> CHUNK_VALUE_SHIFT` is the value.
  static final int CHUNK_COUNT_MASK = 15;
  static final int CHUNK_VALUE_SHIFT = 4;

  // BTYPE=01 predefined Huffman codes. See RFC 1951 section 3.2.6 for the definition.
  static final HuffmanTreeIndex FIXED_INDEX = new HuffmanTreeIndex();

  private final int[] chunks = new int[CHUNK_SIZE];

  private int minCodeLen;
  private int linkMask;
  @Nullable private int[][] links;

  void init(int[] lengths, int start, int size) throws HuffmanCodeDataException {
    int[] count = new int[MAX_CODE_LEN];
    int min = 0;
    int max = 0;
    for (int i = 0; i < size; i++) {
      int n = lengths[start + i];
      if (n == 0) {
        continue;
      }
      if (min == 0 || n < min) {
        min = n;
      }
      if (n > max) {
        max = n;
      }
      count[n]++;
    }

    if (max == 0) {
      this.minCodeLen = 0;
      this.linkMask = 0;
      this.links = null;
      return;
    }

    int[] nextcode = new int[MAX_CODE_LEN];
    {
      int code = 0;
      for (int i = min; i <= max; i++) {
        code <<= 1;
        nextcode[i] = code;
        code += count[i];
      }

      if (code != (1 << max) && !(code == 1 && max == 1)) {
        throw new HuffmanCodeDataException();
      }
    }

    this.minCodeLen = min;
    if (max > CHUNKING_THRESHOLD) {
      int numLinks = 1 << (max - CHUNKING_THRESHOLD);
      this.linkMask = numLinks - 1;

      // numInChunkEntries can be looked up in one shot. Prepare the secondary lookup table.
      int numInChunkEntries = nextcode[CHUNKING_THRESHOLD + 1] >> 1;
      this.links = new int[CHUNK_SIZE - numInChunkEntries][];
      for (int j = numInChunkEntries; j < CHUNK_SIZE; j++) {
        int keyPrefix = (Integer.reverse(j << 16) & 0xFFFF) >> (16 - CHUNKING_THRESHOLD);
        int offset = j - numInChunkEntries;
        if (ENABLE_DEBUG && chunks[keyPrefix] != 0) {
          throw new AssertionError("overwriting chunks");
        }
        chunks[keyPrefix] = (offset << CHUNK_VALUE_SHIFT | CHUNKING_THRESHOLD + 1);
        links[offset] = new int[numLinks];
      }
    } else {
      this.linkMask = 0;
      this.links = null;
    }

    for (int i = 0; i < size; i++) {
      int n = lengths[i + start];
      if (n == 0) {
        continue;
      }

      int code = nextcode[n];
      nextcode[n]++;
      int value = i << CHUNK_VALUE_SHIFT | n;
      int keyPrefix = (Integer.reverse(code << 16) & 0xFFFF) >> (16 - n);
      if (n <= CHUNKING_THRESHOLD) {
        for (int key = keyPrefix; key < CHUNK_SIZE; key += (1 << n)) {
          if (ENABLE_DEBUG && chunks[key] != 0) {
            throw new AssertionError("overwriting chunks");
          }
          chunks[key] = value;
        }
      } else {
        int j = keyPrefix & (CHUNK_SIZE - 1);
        if (ENABLE_DEBUG && (chunks[j] & CHUNK_COUNT_MASK) != CHUNKING_THRESHOLD + 1) {
          throw new AssertionError("not a link");
        }
        int[] linktab = links[chunks[j] >> CHUNK_VALUE_SHIFT];
        keyPrefix >>= CHUNKING_THRESHOLD;
        for (int key = keyPrefix; key < linktab.length; key += (1 << (n - CHUNKING_THRESHOLD))) {
          if (ENABLE_DEBUG && linktab[key] != 0) {
            throw new AssertionError("overwriting chunks");
          }
          linktab[key] = value;
        }
      }
    }

    if (ENABLE_DEBUG) {
      for (int i = 0; i < chunks.length; i++) {
        if (chunks[i] == 0) {
          if (min == max && i % 2 == 1) {
            continue;
          }
          throw new AssertionError("missing chunk");
        }
      }
      if (links != null) {
        for (int i = 0; i < links.length; i++) {
          for (int j = 0; j < links[i].length; j++) {
            if (links[i][j] == 0) {
              throw new AssertionError("missing chunk");
            }
          }
        }
      }
    }
  }

  /**
   * Decode a Huffman symbol.
   *
   * <p>In the deflate format, the symbol is in [0, 287]. There are two Huffman tables for a block.
   * For the meaning of the symbols, see RFC 1951 section 3.2.5.
   *
   * <p>{@code RETVAL >> CHUNK_VALUE_SHIFT} yields the symbol value. {@code RETVAL &
   * CHUNK_COUNT_MASK} yields the symbol code length.This returns -1 if Bitreader cannot read more
   * bytes. The caller should fill the underlying ByteBuffer.
   */
  int lookup(BitReader br) throws HuffmanCodeDataException {
    int n = minCodeLen;
    while (true) {
      while (br.nb < n) {
        if (!br.readMore()) {
          return -1;
        }
      }
      int value = chunks[br.b & (CHUNK_SIZE - 1)];
      n = value & CHUNK_COUNT_MASK;
      if (n > CHUNKING_THRESHOLD) {
        // Second lookup.
        value = links[value >> CHUNK_VALUE_SHIFT][(br.b >> CHUNKING_THRESHOLD) & linkMask];
        n = value & CHUNK_COUNT_MASK;
      }
      if (n <= br.nb) {
        if (n == 0) {
          throw new HuffmanCodeDataException();
        }
        return value;
      }
    }
  }

  static {
    // See RFC 1951 section 3.2.6 for the definition.
    int[] lengths = new int[288];
    for (int i = 0; i < 144; i++) {
      lengths[i] = 8;
    }
    for (int i = 144; i < 256; i++) {
      lengths[i] = 9;
    }
    for (int i = 256; i < 280; i++) {
      lengths[i] = 7;
    }
    for (int i = 280; i < 288; i++) {
      lengths[i] = 8;
    }
    try {
      FIXED_INDEX.init(lengths, 0, lengths.length);
    } catch (HuffmanCodeDataException e) {
      throw new AssertionError(e);
    }
  }

  static class HuffmanCodeDataException extends Exception {}
}
