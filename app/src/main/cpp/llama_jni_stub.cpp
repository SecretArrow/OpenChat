/*
 * Stub JNI bridge built when the pinned llama.cpp source tree is absent.
 * Same symbols as llama_jni.cpp but honestly reports "unavailable" — the
 * Kotlin layer surfaces this instead of pretending inference works (§32).
 */
#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_openchat_android_ai_local_LlamaBridge_nativeIsAvailable(JNIEnv*, jobject) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_openchat_android_ai_local_LlamaBridge_nativeLoad(JNIEnv*, jobject, jstring, jint, jint) {
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_openchat_android_ai_local_LlamaBridge_nativeRequestCancel(JNIEnv*, jobject) {}

extern "C" JNIEXPORT void JNICALL
Java_com_openchat_android_ai_local_LlamaBridge_nativeFree(JNIEnv*, jobject, jlong) {}

extern "C" JNIEXPORT jstring JNICALL
Java_com_openchat_android_ai_local_LlamaBridge_nativeStartCompletion(
        JNIEnv* env, jobject, jlong, jobjectArray, jobjectArray, jint, jfloat, jobject) {
    return env->NewStringUTF("unavailable");
}
