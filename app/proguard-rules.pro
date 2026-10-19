# QuickJS wrapper: JNI looks these classes and members up by name.
-keep class com.whl.quickjs.** { *; }
# kotlinx.serialization: keep generated serializers of @Serializable classes.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Release builds never log.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
