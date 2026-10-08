#pragma once

#include <jni.h>

// Hooks in-process microphone reads. Returns true when at least one symbol was patched.
jboolean enableMicTap(JNIEnv *env, jclass clazz, jstring path, jbyteArray pcm, jint rate, jint channels);

// Stops Camera1 preview from reaching the app. The Java layer draws the replacement.
jboolean enableCameraBlock(JNIEnv *env, jclass clazz);
