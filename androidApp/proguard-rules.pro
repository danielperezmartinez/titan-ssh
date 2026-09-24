# R8 rules for the release build.

# Keep class names readable in stack traces from users; only unused code is
# removed and the rest optimized.
-dontobfuscate

# BouncyCastle registers its algorithms as class names looked up by
# reflection (provider SPI tables), and so does the eddsa provider.
-keep class org.bouncycastle.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }

# sshj: algorithm factories, key-file formats and the transport are selected
# at runtime from name lists.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }

# Optional dependencies of the libraries above that are absent on Android.
-dontwarn javax.naming.**
-dontwarn org.slf4j.impl.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
-dontwarn sun.security.**
-dontwarn java.lang.management.**
