# NewPipe Extractor memakai refleksi & Rhino (JavaScript) untuk dekripsi stream.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.bytify.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
