#pragma once

#include <jni.h>

// Hooks in-process microphone reads. Returns true when at least one symbol was patched.
jboolean enableMicTap(JNIEnv *env, jclass clazz, jstring path, jbyteArray pcm, jint rate, jint channels,
                      jint mode, jint profile, jfloat input_gain, jfloat pitch_semitones,
                      jfloat formant_shift, jboolean underflow_silence);
void processMicBytes(JNIEnv *env, jclass clazz, jbyteArray pcm, jint offset, jint byte_count,
                     jint rate, jint channels);
void processMicShorts(JNIEnv *env, jclass clazz, jshortArray pcm, jint offset, jint sample_count,
                      jint rate, jint channels);
void processMicFloats(JNIEnv *env, jclass clazz, jfloatArray pcm, jint offset, jint sample_count,
                      jint rate, jint channels);
void processMicDirect(JNIEnv *env, jclass clazz, jobject pcm, jint byte_count, jint rate,
                      jint channels, jboolean float_pcm);

// Stops Camera1 preview from reaching the app. The Java layer draws the replacement.
jboolean enableCameraBlock(JNIEnv *env, jclass clazz);
