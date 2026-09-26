# bytedeco loads its JNI glue reflectively.
-keep class org.bytedeco.** { *; }
-dontwarn org.bytedeco.**
-dontwarn java.awt.**
-dontwarn javax.**

# JNI entry points of the upscaler.
-keep class com.supervideo.core.upscale.NativeUpscaler { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keep,includedescriptorclasses class com.supervideo.**$$serializer { *; }
-keepclassmembers class com.supervideo.** {
    *** Companion;
}
-keepclasseswithmembers class com.supervideo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
