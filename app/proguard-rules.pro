# A rule exists only where something is looked up by name at runtime (reflection, JNI, name-keyed maps).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Hilt keys the ViewModel map by class name; AGP does not read META-INF/proguard of library subprojects.
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.lifecycle.HiltViewModel class *

# kotlinx.serialization: generated serializers, and INSTANCE of @Serializable object routes (an object route that is
# registered but never navigated to otherwise loses them in R8 full mode: "Serializer for class 'a' is not found").
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers @kotlinx.serialization.Serializable class com.bittool.signer.** {
    public static ** INSTANCE;
    static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Release builds never log below warning.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
