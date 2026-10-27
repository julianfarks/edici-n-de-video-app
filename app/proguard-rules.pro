# kotlinx.serialization — keep generated serializers for the project file format.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vanta.studio.**$$serializer { *; }
-keepclassmembers class com.vanta.studio.** {
    *** Companion;
}
-keepclasseswithmembers class com.vanta.studio.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep enum com.vanta.studio.domain.** { *; }

# Vosk / JNA load native code through reflection.
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
