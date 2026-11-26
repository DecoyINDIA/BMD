# Test harness entry points only. These rules are never applied to the production APK.
-keep class com.blackmarlin.downloader.releasecheck.ReleaseRuntimeTest { *; }
-keep class androidx.test.runner.AndroidJUnitRunner { *; }
-keep class androidx.test.ext.junit.runners.AndroidJUnit4 { *; }
-keep class org.junit.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
