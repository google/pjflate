# Pure Java Flate

Java standard library's `java.util.zip.Inflater` is a wrapper of zlib, and it
uses JNI. This is one of those attempts to implement the deflate format's
inflater in pure Java.

The code is mostly a direct translation of [Go's
`compress/flate`](https://godoc.org/compress/flate). Huge thanks to the Go
authors and contributors for the readable and annotated implementation.

This is not an official Google product. This is the author's hobby project. Not
for production use.

## Motivations

*   This is not an attempt to make it fast. In fact, it's much slower than the
    Java standard `Inflater`. This is more like to avoid the GCLocker impact.

    JNI code in general is not GC aware, and in order to avoid arrays to be
    moved by JVM GC, it needs to pin primitive arrays. While pinning, JVM GC is
    paused. After this pinning is done, JVM can trigger a GC (GCLocker initiated
    GC). Because no GC is done while this pinning, depending on the heuristics
    JVM can trigger a full GC. For certain applications, it's more favorable to
    keep concurrent GCs running to prevent a full GC.

*   There are some pure Java deflate implementations out there, but the author
    wanted to have a `ByteBuffer` friendly interface. It's easy to make an
    adapter though (see the test).

    Starting from Java SE 11, the standard `Inflater` can take a `ByteBuffer`.
    In Open JDK implementation, it checks if it's a direct buffer and if it is,
    it doesn't lock GC. If GCLocker is a concern and Open JDK 11 or later is
    used, it's better to use a direct buffer with the standard `Inflater`. It
    gives you a better speed than pure Java implementations and you can avoid
    the GCLocker issue.

*   It seems it's a good size for a weekend hobby project.

## Build & Test

Use [Bazel](https://bazel.build). Run `download_testdata.sh` before running the
test. It downloads the test data.

## Speed comparison

This is not a serious benchmark, but it seems that it's 4x slower than the
standard library Inflater (see the test).

    For 100MiB file (enwik8):

    java.util.zip.InflaterStream: 361ms
    java.util.zip.Inflater: 326ms
    Inflater(HeapBuffer): 1201ms
    Inflater(DirectBuffer): 1097ms

    For 1GiB file (enwik9):

    java.util.zip.InflaterStream: 2953ms
    java.util.zip.Inflater: 2889ms
    Inflater(HeapBuffer): 9772ms
    Inflater(DirectBuffer): 9783ms

The first implementation was actually using ByteBuffer#array, and it was 3x
slower than the standard library. The extra slowness came from the DirectBuffer
support.
