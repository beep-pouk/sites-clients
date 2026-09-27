# Keeps the release build's R8 behavior as close as possible to the debug build's (shrinking
# only): obfuscation adds little for an app whose protocol is public, while untested
# optimizations/renaming risk breaking the reflection-heavy crypto and serialization code paths.
-dontobfuscate
-dontoptimize
