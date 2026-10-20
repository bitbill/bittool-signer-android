/*
 * BitTool Signer — the native host of the offline signing engine.
 *
 * A deliberately small JNI surface over QuickJS (vendored, quickjs/VERSION): create a runtime, compile verified
 * source to bytecode, evaluate bytecode, call the engine's `dispatch(json)`, destroy the runtime. Strings cross the
 * boundary as UTF-8 byte arrays, never as JNI strings (whose "modified UTF-8" mangles U+0000 and supplementary
 * characters). The runtime has no module loader, no std/os libraries, no timers, no file or network access; fresh
 * runtimes are created without the Eval intrinsic, so `eval` and `Function` are unavailable to engine code.
 *
 * Every function must be called on the thread that created the runtime (QuickJS records its stack top).
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "quickjs/quickjs.h"

typedef struct {
    JSRuntime *rt;
    JSContext *ctx;
    JSValue dispatch; /* BitToolSignerEngine.dispatch, captured after the bundle ran; JS_UNDEFINED before. */
} SignerRuntime;

static void throw_java(JNIEnv *env, const char *cls, const char *message) {
    jclass c = (*env)->FindClass(env, cls);
    if (c != NULL) (*env)->ThrowNew(env, c, message);
}

/* Reports a pending JS exception as a Java exception. The JS message is passed through for debugging only; the Kotlin
 * side never shows it (it maps every native failure to a fixed, input-free message). */
static void throw_pending(JNIEnv *env, JSContext *ctx, const char *fallback) {
    JSValue exception = JS_GetException(ctx);
    const char *text = JS_IsNull(exception) ? NULL : JS_ToCString(ctx, exception);
    throw_java(env, "com/bittool/signer/core/engine/internal/NativeEngineException", text != NULL ? text : fallback);
    if (text != NULL) JS_FreeCString(ctx, text);
    JS_FreeValue(ctx, exception);
}

/* __bitSignerRandom(n): n bytes (1..65536) from the platform CSPRNG (bionic arc4random_buf, seeded by the kernel),
 * as an ArrayBuffer. The bootstrap captures it, deletes the global and builds crypto.getRandomValues on it. */
static JSValue js_random(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int32_t n;
    if (argc < 1 || JS_ToInt32(ctx, &n, argv[0]) < 0) return JS_EXCEPTION;
    if (n < 1 || n > 65536) return JS_UNDEFINED;
    uint8_t *buf = malloc((size_t) n);
    if (buf == NULL) return JS_ThrowOutOfMemory(ctx);
    arc4random_buf(buf, (size_t) n);
    JSValue out = JS_NewArrayBufferCopy(ctx, buf, (size_t) n);
    memset(buf, 0, (size_t) n);
    free(buf);
    return out;
}

static int add_intrinsics(JSContext *ctx) {
    return JS_AddIntrinsicBaseObjects(ctx) || JS_AddIntrinsicDate(ctx) || JS_AddIntrinsicStringNormalize(ctx) ||
           JS_AddIntrinsicRegExp(ctx) || JS_AddIntrinsicJSON(ctx) || JS_AddIntrinsicProxy(ctx) ||
           JS_AddIntrinsicMapSet(ctx) || JS_AddIntrinsicTypedArrays(ctx) || JS_AddIntrinsicPromise(ctx) ||
           JS_AddIntrinsicWeakRef(ctx);
}

JNIEXPORT jlong JNICALL
Java_com_bittool_signer_core_engine_internal_NativeQuickJs_newRuntime(JNIEnv *env, jclass clazz, jlong memory_limit,
                                                                     jlong max_stack, jboolean compiler) {
    SignerRuntime *r = calloc(1, sizeof(SignerRuntime));
    if (r == NULL) return 0;
    r->rt = JS_NewRuntime();
    if (r->rt == NULL) {
        free(r);
        return 0;
    }
    JS_SetMemoryLimit(r->rt, (size_t) memory_limit);
    JS_SetMaxStackSize(r->rt, (size_t) max_stack);
    /* The compiler runtime needs the parser (Eval intrinsic); engine runtimes only ever evaluate bytecode. */
    r->ctx = compiler ? JS_NewContext(r->rt) : JS_NewContextRaw(r->rt);
    if (r->ctx == NULL || (!compiler && add_intrinsics(r->ctx))) {
        if (r->ctx != NULL) JS_FreeContext(r->ctx);
        JS_FreeRuntime(r->rt);
        free(r);
        return 0;
    }
    r->dispatch = JS_UNDEFINED;
    JSValue global = JS_GetGlobalObject(r->ctx);
    JS_SetPropertyStr(r->ctx, global, "__bitSignerRandom", JS_NewCFunction(r->ctx, js_random, "__bitSignerRandom", 1));
    JS_FreeValue(r->ctx, global);
    return (jlong) (intptr_t) r;
}

JNIEXPORT void JNICALL
Java_com_bittool_signer_core_engine_internal_NativeQuickJs_freeRuntime(JNIEnv *env, jclass clazz, jlong handle) {
    SignerRuntime *r = (SignerRuntime *) (intptr_t) handle;
    if (r == NULL) return;
    JS_FreeValue(r->ctx, r->dispatch);
    JS_FreeContext(r->ctx);
    JS_FreeRuntime(r->rt);
    free(r);
}

/* Compiles UTF-8 source (any bytes, including U+0000) as global code and returns its bytecode. */
JNIEXPORT jbyteArray JNICALL
Java_com_bittool_signer_core_engine_internal_NativeQuickJs_compile(JNIEnv *env, jclass clazz, jlong handle,
                                                                  jbyteArray source, jstring file_name) {
    SignerRuntime *r = (SignerRuntime *) (intptr_t) handle;
    jsize length = (*env)->GetArrayLength(env, source);
    /* JS_Eval requires a NUL after the input. */
    char *text = malloc((size_t) length + 1);
    if (text == NULL) {
        throw_java(env, "java/lang/OutOfMemoryError", "engine source");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, source, 0, length, (jbyte *) text);
    text[length] = '\0';
    const char *name = (*env)->GetStringUTFChars(env, file_name, NULL);
    JSValue function = JS_Eval(r->ctx, text, (size_t) length, name,
                               JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_COMPILE_ONLY);
    (*env)->ReleaseStringUTFChars(env, file_name, name);
    free(text);
    if (JS_IsException(function)) {
        throw_pending(env, r->ctx, "compile failed");
        return NULL;
    }
    size_t size = 0;
    uint8_t *bytecode = JS_WriteObject(r->ctx, &size, function, JS_WRITE_OBJ_BYTECODE);
    JS_FreeValue(r->ctx, function);
    if (bytecode == NULL) {
        throw_pending(env, r->ctx, "bytecode serialisation failed");
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) size);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize) size, (const jbyte *) bytecode);
    js_free(r->ctx, bytecode);
    return out;
}

/* Evaluates bytecode produced by compile() in the same process. Never fed bytecode from storage. */
JNIEXPORT void JNICALL
Java_com_bittool_signer_core_engine_internal_NativeQuickJs_evalBytecode(JNIEnv *env, jclass clazz, jlong handle,
                                                                       jbyteArray bytecode) {
    SignerRuntime *r = (SignerRuntime *) (intptr_t) handle;
    jsize length = (*env)->GetArrayLength(env, bytecode);
    jbyte *bytes = (*env)->GetByteArrayElements(env, bytecode, NULL);
    JSValue function = JS_ReadObject(r->ctx, (const uint8_t *) bytes, (size_t) length, JS_READ_OBJ_BYTECODE);
    (*env)->ReleaseByteArrayElements(env, bytecode, bytes, JNI_ABORT);
    if (JS_IsException(function)) {
        throw_pending(env, r->ctx, "bytecode rejected");
        return;
    }
    JSValue result = JS_EvalFunction(r->ctx, function);
    if (JS_IsException(result)) {
        throw_pending(env, r->ctx, "evaluation failed");
        return;
    }
    JS_FreeValue(r->ctx, result);
}

/* Resolves BitToolSignerEngine.dispatch once the bundle has run. Returns false if it is missing. */
JNIEXPORT jboolean JNICALL
Java_com_bittool_signer_core_engine_internal_NativeQuickJs_bindDispatch(JNIEnv *env, jclass clazz, jlong handle) {
    SignerRuntime *r = (SignerRuntime *) (intptr_t) handle;
    JSValue global = JS_GetGlobalObject(r->ctx);
    JSValue engine = JS_GetPropertyStr(r->ctx, global, "BitToolSignerEngine");
    JS_FreeValue(r->ctx, global);
    if (!JS_IsObject(engine)) {
        JS_FreeValue(r->ctx, engine);
        return JNI_FALSE;
    }
    JSValue dispatch = JS_GetPropertyStr(r->ctx, engine, "dispatch");
    JS_FreeValue(r->ctx, engine);
    if (!JS_IsFunction(r->ctx, dispatch)) {
        JS_FreeValue(r->ctx, dispatch);
        return JNI_FALSE;
    }
    JS_FreeValue(r->ctx, r->dispatch);
    r->dispatch = dispatch;
    return JNI_TRUE;
}

/* dispatch(requestJson) with UTF-8 bytes in and out. The request text is an argument, never interpolated into code. */
JNIEXPORT jbyteArray JNICALL
Java_com_bittool_signer_core_engine_internal_NativeQuickJs_dispatch(JNIEnv *env, jclass clazz, jlong handle,
                                                                   jbyteArray request) {
    SignerRuntime *r = (SignerRuntime *) (intptr_t) handle;
    if (!JS_IsFunction(r->ctx, r->dispatch)) {
        throw_java(env, "com/bittool/signer/core/engine/internal/NativeEngineException", "dispatch not bound");
        return NULL;
    }
    jsize length = (*env)->GetArrayLength(env, request);
    jbyte *bytes = (*env)->GetByteArrayElements(env, request, NULL);
    JSValue argument = JS_NewStringLen(r->ctx, (const char *) bytes, (size_t) length);
    (*env)->ReleaseByteArrayElements(env, request, bytes, JNI_ABORT);
    if (JS_IsException(argument)) {
        throw_pending(env, r->ctx, "request rejected");
        return NULL;
    }
    JSValue response = JS_Call(r->ctx, r->dispatch, JS_UNDEFINED, 1, &argument);
    JS_FreeValue(r->ctx, argument);
    if (JS_IsException(response)) {
        throw_pending(env, r->ctx, "dispatch failed");
        return NULL;
    }
    if (!JS_IsString(response)) {
        JS_FreeValue(r->ctx, response);
        throw_java(env, "com/bittool/signer/core/engine/internal/NativeEngineException", "dispatch returned no text");
        return NULL;
    }
    size_t size = 0;
    const char *text = JS_ToCStringLen2(r->ctx, &size, response, 0);
    JS_FreeValue(r->ctx, response);
    if (text == NULL) {
        throw_pending(env, r->ctx, "response conversion failed");
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) size);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize) size, (const jbyte *) text);
    JS_FreeCString(r->ctx, text);
    /* Collect garbage from this call now rather than letting secret-derived objects linger in the heap. */
    JS_RunGC(r->rt);
    return out;
}

JNIEXPORT jlong JNICALL
Java_com_bittool_signer_core_engine_internal_NativeQuickJs_memoryUsed(JNIEnv *env, jclass clazz, jlong handle) {
    SignerRuntime *r = (SignerRuntime *) (intptr_t) handle;
    JSMemoryUsage usage;
    JS_ComputeMemoryUsage(r->rt, &usage);
    return (jlong) usage.memory_used_size;
}

JNIEXPORT jstring JNICALL
Java_com_bittool_signer_core_engine_internal_NativeQuickJs_version(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, CONFIG_VERSION);
}
