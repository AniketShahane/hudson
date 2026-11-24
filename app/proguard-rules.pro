-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepclassmembers @kotlinx.serialization.Serializable class app.hudson.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Personal sideloaded build: keep real names for readable stack traces.
-dontobfuscate
