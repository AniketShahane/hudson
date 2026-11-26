-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepclassmembers @kotlinx.serialization.Serializable class app.hudson.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# The places in the app's navigation are saved and restored by their serializers.
-keep class app.hudson.ui.*Key { *; }
-keep class app.hudson.ui.*Key$* { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Personal sideloaded build: keep real names for readable stack traces.
-dontobfuscate
