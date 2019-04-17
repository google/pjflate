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
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Inflater.
 *
 * <p>The code is a pure Java port of Go's flate code
 * (https://golang.org/src/compress/flate/inflate.go).
 */
public class Inflater {
  private static final int MAX_LITERALS = 286;
  private static final int MAX_DISTANCES = 30;
  private static final int[] META_CODE_ORDER =
      new int[] {
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
      };

  private final BitReader br = new BitReader();
  private final HuffmanTreeIndex tempIndex1 = new HuffmanTreeIndex();
  private final HuffmanTreeIndex tempIndex2 = new HuffmanTreeIndex();
  private final InflateOutputBuffer out = new InflateOutputBuffer();
  private final int[] bits = new int[MAX_LITERALS + MAX_DISTANCES];
  private final int[] codebits = new int[META_CODE_ORDER.length];

  private InternalState internalState;
  private boolean finalBlock;
  private int dataBlockCopyLength;
  private int historyCopyDistance;
  private int historyCopyLength;
  @Nullable private CustomHuffmanBlockReadState customHuffmanBlockReadState;
  @Nullable private HuffmanTreeIndex distanceIndex;
  @Nullable private HuffmanTreeIndex lengthIndex;

  public Inflater() {
    reset();
  }

  public void reset() {
    internalState = InternalState.READ_NEXT_BLOCK;
    finalBlock = false;
    br.b = 0;
    br.nb = 0;
    out.reset();
  }

  public State inflate(ByteBuffer dst, ByteBuffer src) throws InflationException {
    try {
      br.setByteBuffer(src);
      while (true) {
        if (out.readAvailable() > 0) {
          if (!out.writeTo(dst)) {
            return State.NEED_MORE_OUTPUT;
          }
        }
        if (out.readAvailable() == 0 && finalBlock) {
          if (internalState == InternalState.READ_NEXT_BLOCK) {
            return State.DONE;
          }
        }

        State s;
        switch (internalState) {
          case READ_NEXT_BLOCK:
            s = readNextBlock();
            break;
          case PROCESS_DATA_BLOCK:
            s = processDataBlock(src);
            break;
          case PROCESS_HISTORY_COPY_LENGTH:
            s = processHistoryCopyLength();
            break;
          case PROCESS_HISTORY_COPY_DISTANCE:
            s = processHistoryCopyDistance();
            break;
          case PROCESS_HISTORY_COPY:
            s = processHistoryCopy();
            break;
          case READ_CUSTOM_HUFFMAN_BLOCK:
            s = processCustomHuffmanBlock();
            break;
          default:
            throw new AssertionError();
        }
        if (s != null) {
          return s;
        }
      }
    } finally {
      br.setByteBuffer(null);
    }
  }

  @Nullable
  private State readNextBlock() throws InflationException {
    while (br.nb < 3) {
      if (!br.readMore()) {
        return State.NEED_MORE_INPUT;
      }
    }
    finalBlock = (br.b & 0b1) == 1;
    br.b >>= 1;
    int type = br.b & 0b11;
    br.b >>= 2;
    br.nb -= 3;

    switch (type) {
      case 0:
        internalState = InternalState.PROCESS_DATA_BLOCK;
        br.b = 0;
        br.nb = 0;
        dataBlockCopyLength = 0;
        return null;
      case 1:
        internalState = InternalState.PROCESS_HISTORY_COPY_LENGTH;
        lengthIndex = HuffmanTreeIndex.FIXED_INDEX;
        distanceIndex = null;
        historyCopyLength = 0;
        historyCopyDistance = 0;
        return null;
      case 2:
        internalState = InternalState.READ_CUSTOM_HUFFMAN_BLOCK;
        customHuffmanBlockReadState = new CustomHuffmanBlockReadState();
        lengthIndex = null;
        distanceIndex = null;
        return null;
    }
    throw new InflationException();
  }

  @Nullable
  private State processDataBlock(ByteBuffer src) throws InflationException {
    if (dataBlockCopyLength == 0) {
      if (src.remaining() < 4) {
        return State.NEED_MORE_INPUT;
      }

      try (CommitGuard g = new CommitGuard(src)) {
        ByteOrder originalOrder = src.order();
        src.order(ByteOrder.LITTLE_ENDIAN);
        dataBlockCopyLength = src.getShort();
        int inverted = ~src.getShort();
        if ((dataBlockCopyLength & 0xFFFF) != (inverted & 0xFFFF)) {
          throw new InflationException();
        }
        src.order(originalOrder);
        g.commit();
      }

      if (dataBlockCopyLength == 0) {
        internalState = InternalState.READ_NEXT_BLOCK;
        return null;
      }
    }

    int len = Math.min(dataBlockCopyLength, src.remaining());
    int written = out.writeFrom(src, len);
    dataBlockCopyLength -= written;
    if (written < len) {
      return State.NEED_MORE_OUTPUT;
    }
    if (dataBlockCopyLength > 0) {
      return State.NEED_MORE_INPUT;
    }
    internalState = InternalState.READ_NEXT_BLOCK;
    return null;
  }

  @Nullable
  private State processHistoryCopyLength() throws InflationException {
    try {
      while (true) {
        int value = Objects.requireNonNull(lengthIndex).lookup(br);
        if (value < 0) {
          return State.NEED_MORE_INPUT;
        }
        int symbol = value >> HuffmanTreeIndex.CHUNK_VALUE_SHIFT;
        int symbolLen = value & HuffmanTreeIndex.CHUNK_COUNT_MASK;

        int n;
        int length;
        if (symbol < 256) {
          if (out.writeAvailable() == 0) {
            return State.NEED_MORE_OUTPUT;
          }
          out.write((byte) symbol);
          br.b >>= symbolLen;
          br.nb -= symbolLen;
          continue;
        } else if (symbol == 256) {
          br.b >>= symbolLen;
          br.nb -= symbolLen;
          internalState = InternalState.READ_NEXT_BLOCK;
          return null;
        } else if (symbol < 265) {
          length = symbol - (257 - 3);
          n = 0;
        } else if (symbol < 269) {
          length = symbol * 2 - (265 * 2 - 11);
          n = 1;
        } else if (symbol < 273) {
          length = symbol * 4 - (269 * 4 - 19);
          n = 2;
        } else if (symbol < 277) {
          length = symbol * 8 - (273 * 8 - 35);
          n = 3;
        } else if (symbol < 281) {
          length = symbol * 16 - (277 * 16 - 67);
          n = 4;
        } else if (symbol < 285) {
          length = symbol * 32 - (281 * 32 - 131);
          n = 5;
        } else if (symbol < MAX_LITERALS) {
          length = 258;
          n = 0;
        } else {
          throw new InflationException();
        }
        if (n > 0) {
          while (br.nb < symbolLen + n) {
            if (!br.readMore()) {
              return State.NEED_MORE_INPUT;
            }
          }
          length += (br.b >> symbolLen) & ((1 << n) - 1);
        }
        br.b >>= symbolLen + n;
        br.nb -= symbolLen + n;
        historyCopyLength = length;
        internalState = InternalState.PROCESS_HISTORY_COPY_DISTANCE;
        return null;
      }
    } catch (HuffmanTreeIndex.HuffmanCodeDataException e) {
      throw new InflationException(e);
    }
  }

  @Nullable
  private State processHistoryCopyDistance() throws InflationException {
    try {
      int dist;
      int bitConsumed;
      if (distanceIndex == null) {
        while (br.nb < 5) {
          if (!br.readMore()) {
            return State.NEED_MORE_INPUT;
          }
        }
        dist = Integer.reverse((br.b & 0x1F) << 27);
        bitConsumed = 5;
      } else {
        int value = distanceIndex.lookup(br);
        if (value < 0) {
          return State.NEED_MORE_INPUT;
        }
        dist = value >> HuffmanTreeIndex.CHUNK_VALUE_SHIFT;
        bitConsumed = value & HuffmanTreeIndex.CHUNK_COUNT_MASK;
      }

      if (dist < 4) {
        dist++;
      } else if (dist < MAX_DISTANCES) {
        int nb = (dist - 2) >> 1;
        int extra = (dist & 1) << nb;
        while (br.nb < bitConsumed + nb) {
          if (!br.readMore()) {
            return State.NEED_MORE_INPUT;
          }
        }
        extra |= (br.b >> bitConsumed) & ((1 << nb) - 1);
        bitConsumed += nb;
        dist = (1 << (nb + 1)) + 1 + extra;
      } else {
        throw new InflationException();
      }

      if (dist > out.historySize()) {
        throw new InflationException();
      }
      br.b >>= bitConsumed;
      br.nb -= bitConsumed;
      historyCopyDistance = dist;
      internalState = InternalState.PROCESS_HISTORY_COPY;
      return null;
    } catch (HuffmanTreeIndex.HuffmanCodeDataException e) {
      throw new InflationException(e);
    }
  }

  @Nullable
  private State processHistoryCopy() {
    if (out.writeAvailable() == 0) {
      return State.NEED_MORE_OUTPUT;
    }
    if (historyCopyLength > 0) {
      int written = out.writeCopy(historyCopyDistance, historyCopyLength);
      historyCopyLength -= written;

      if (out.writeAvailable() == 0 || historyCopyLength > 0) {
        return State.NEED_MORE_OUTPUT;
      }
    }
    internalState = InternalState.PROCESS_HISTORY_COPY_LENGTH;
    historyCopyLength = 0;
    historyCopyDistance = 0;
    return null;
  }

  @Nullable
  private State processCustomHuffmanBlock() throws InflationException {
    CustomHuffmanBlockReadState state = Objects.requireNonNull(customHuffmanBlockReadState);

    if (state.numDistance == 0) {
      while (br.nb < 5 + 5 + 4) {
        if (!br.readMore()) {
          return State.NEED_MORE_INPUT;
        }
      }

      state.numLiteral = (br.b & 0x1F) + 257;
      if (state.numLiteral > MAX_LITERALS) {
        throw new InflationException();
      }
      br.b >>= 5;
      state.numDistance = (br.b & 0x1F) + 1;
      if (state.numDistance > MAX_DISTANCES) {
        throw new InflationException();
      }
      br.b >>= 5;
      state.numCodeLen = (br.b & 0x0F) + 4;
      br.b >>= 4;
      br.nb -= 5 + 5 + 4;
    }

    if (state.codeIndex == null) {
      for (; state.numReadCodeLen < state.numCodeLen; state.numReadCodeLen++) {
        while (br.nb < 3) {
          if (!br.readMore()) {
            return State.NEED_MORE_INPUT;
          }
        }
        codebits[META_CODE_ORDER[state.numReadCodeLen]] = br.b & 0x07;
        br.b >>= 3;
        br.nb -= 3;
      }
      for (int i = state.numCodeLen; i < META_CODE_ORDER.length; i++) {
        codebits[META_CODE_ORDER[i]] = 0;
      }

      try {
        state.codeIndex = tempIndex1;
        state.codeIndex.init(codebits, 0, codebits.length);
      } catch (HuffmanTreeIndex.HuffmanCodeDataException e) {
        throw new InflationException(e);
      }
    }

    try {
      while (state.numReadLen < state.numLiteral + state.numDistance) {
        int value = state.codeIndex.lookup(br);
        if (value < 0) {
          return State.NEED_MORE_INPUT;
        }
        int symbol = value >> HuffmanTreeIndex.CHUNK_VALUE_SHIFT;
        int symbolLen = value & HuffmanTreeIndex.CHUNK_COUNT_MASK;
        if (symbol < 16) {
          bits[state.numReadLen] = symbol;
          br.b >>= symbolLen;
          br.nb -= symbolLen;
          state.numReadLen++;
          continue;
        }

        int rep;
        int nb;
        int b;
        switch (symbol) {
          case 16:
            rep = 3;
            nb = 2;
            if (state.numReadLen == 0) {
              throw new InflationException();
            }
            b = bits[state.numReadLen - 1];
            break;
          case 17:
            rep = 3;
            nb = 3;
            b = 0;
            break;
          case 18:
            rep = 11;
            nb = 7;
            b = 0;
            break;
          default:
            throw new AssertionError();
        }
        while (br.nb < symbolLen + nb) {
          if (!br.readMore()) {
            return State.NEED_MORE_INPUT;
          }
        }
        rep += (br.b >> symbolLen) & ((1 << nb) - 1);
        br.b >>= symbolLen + nb;
        br.nb -= symbolLen + nb;
        if (state.numReadLen > state.numLiteral + state.numDistance) {
          throw new InflationException();
        }
        for (int j = 0; j < rep; j++) {
          bits[state.numReadLen] = b;
          state.numReadLen++;
        }
      }

      lengthIndex = tempIndex1;
      lengthIndex.init(bits, 0, state.numLiteral);
      distanceIndex = tempIndex2;
      distanceIndex.init(bits, state.numLiteral, state.numDistance);
    } catch (HuffmanTreeIndex.HuffmanCodeDataException e) {
      throw new InflationException(e);
    }

    internalState = InternalState.PROCESS_HISTORY_COPY_LENGTH;
    customHuffmanBlockReadState = null;
    historyCopyLength = 0;
    historyCopyDistance = 0;
    return null;
  }

  public enum State {
    NEED_MORE_INPUT,
    NEED_MORE_OUTPUT,
    DONE,
  }

  private enum InternalState {
    READ_NEXT_BLOCK,
    PROCESS_DATA_BLOCK,
    PROCESS_HISTORY_COPY_LENGTH,
    PROCESS_HISTORY_COPY_DISTANCE,
    PROCESS_HISTORY_COPY,
    READ_CUSTOM_HUFFMAN_BLOCK,
  }

  private static class CustomHuffmanBlockReadState {
    int numLiteral;
    int numDistance;
    int numCodeLen;

    int numReadCodeLen;
    int numReadLen;

    HuffmanTreeIndex codeIndex;
  }

  public static class InflationException extends Exception {
    InflationException() {
      super();
    }

    InflationException(Throwable cause) {
      super(cause);
    }
  }
}
