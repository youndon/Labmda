# ------------------------------------------------------------------
# Kotlinx Serialization Rules
# ------------------------------------------------------------------
# Preserve annotations and type signatures required for runtime lookup
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Keep all classes annotated with @Serializable and their fields
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    @kotlinx.serialization.Serializable *;
}

# Preserve generated companion serializers (e.g. UserProfile$$serializer)
-keepnames class *$$serializer {
    static *$$serializer INSTANCE;
}
-keepclassmembers class * {
    *** Companion;
}

# ------------------------------------------------------------------
# Firebase GitLive Rules
# ------------------------------------------------------------------
# Keep GitLive and Native Firebase SDK wrappers
-keep class dev.gitlive.firebase.** { *; }
-keep class com.google.firebase.** { *; }