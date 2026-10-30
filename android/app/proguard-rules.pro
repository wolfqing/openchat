# R8 rules for the release preview.

# Tor runs through JNI and its control-port library; keep both whole.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }

# Optional platform integrations these libraries probe for and do without.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.naming.**
