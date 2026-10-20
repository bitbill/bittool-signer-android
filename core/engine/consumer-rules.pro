# signer_jni.c binds NativeQuickJs's native methods by name and throws NativeEngineException via FindClass.
-keepclasseswithmembernames class com.bittool.signer.core.engine.internal.NativeQuickJs { native <methods>; }
-keep class com.bittool.signer.core.engine.internal.NativeEngineException { <init>(java.lang.String); }
