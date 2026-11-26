# Test harness entry points only. These rules are never applied to the production APK.
-keep class com.blackmarlin.downloader.releasecheck.ReleaseRuntimeTest { *; }
-keep class androidx.test.runner.AndroidJUnitRunner { *; }
-keep class androidx.test.ext.junit.runners.AndroidJUnit4 { *; }
-keep class org.junit.** { *; }
# Preserve the real tracing implementation and verify its DEX class definition.
-keep class androidx.tracing.** { *; }
# Prevent the independently shrunk runner libraries from sharing obfuscated class
# names with the immutable target. The archive verifier rejects every overlap.
-repackageclasses com.blackmarlin.downloader.releasecheck.runtime
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
