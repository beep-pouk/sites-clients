# Keep Tink's reflection-based provider registration working under R8.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.securechat.**$$serializer { *; }
-keepclassmembers class com.securechat.** {
    *** Companion;
}
-keepclasseswithmembers class com.securechat.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**
