# Stockfish Binary Setup

Place the Stockfish binary for each architecture here:

```
jniLibs/
  arm64-v8a/libstockfish.so     <- 64-bit ARM (most modern phones)
  armeabi-v7a/libstockfish.so   <- 32-bit ARM (older phones)
```

## How to get the binary

1. Download Stockfish for Android from https://stockfishchess.org/download/
2. Extract the binary for your target architecture
3. Rename it to `libstockfish.so` (the `lib` prefix and `.so` extension are required for Android to extract it)
4. Place it in the matching architecture folder above

The app looks for `libstockfish.so` in the native library directory at runtime.
With `useLegacyPackaging = true` in build.gradle, it gets extracted to disk where it can be executed.
