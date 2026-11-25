-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepclassmembers @kotlinx.serialization.Serializable class app.hudson.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Glance starts a widget tap handler by name (Class.forName + no-arg constructor). R8 cannot see
# that and removed the constructor, so taps on the widget did nothing in the release build.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Personal sideloaded build: keep real names for readable stack traces.
-dontobfuscate
