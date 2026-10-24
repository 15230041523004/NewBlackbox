#include "ObbMethodHook.h"
#include "Log.h"

#include <cstdarg>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

namespace {

char kObbMarker;
char kStubMarker;

jmethodID (*origGetMethodID)(JNIEnv *, jclass, const char *, const char *) = nullptr;
jmethodID (*origGetStaticMethodID)(JNIEnv *, jclass, const char *, const char *) = nullptr;
jobject (*origCallObjectMethodV)(JNIEnv *, jobject, jmethodID, va_list) = nullptr;
jobject (*origCallObjectMethodA)(JNIEnv *, jobject, jmethodID, const jvalue *) = nullptr;
jobject (*origCallStaticObjectMethodV)(JNIEnv *, jclass, jmethodID, va_list) = nullptr;
jobject (*origCallStaticObjectMethodA)(JNIEnv *, jclass, jmethodID, const jvalue *) = nullptr;
void (*origCallVoidMethodV)(JNIEnv *, jobject, jmethodID, va_list) = nullptr;
void (*origCallVoidMethodA)(JNIEnv *, jobject, jmethodID, const jvalue *) = nullptr;
void (*origCallStaticVoidMethodV)(JNIEnv *, jclass, jmethodID, va_list) = nullptr;
void (*origCallStaticVoidMethodA)(JNIEnv *, jclass, jmethodID, const jvalue *) = nullptr;
jboolean (*origCallBooleanMethodV)(JNIEnv *, jobject, jmethodID, va_list) = nullptr;
jboolean (*origCallBooleanMethodA)(JNIEnv *, jobject, jmethodID, const jvalue *) = nullptr;
jboolean (*origCallStaticBooleanMethodV)(JNIEnv *, jclass, jmethodID, va_list) = nullptr;
jboolean (*origCallStaticBooleanMethodA)(JNIEnv *, jclass, jmethodID, const jvalue *) = nullptr;
jint (*origCallIntMethodV)(JNIEnv *, jobject, jmethodID, va_list) = nullptr;
jint (*origCallIntMethodA)(JNIEnv *, jobject, jmethodID, const jvalue *) = nullptr;
jint (*origCallStaticIntMethodV)(JNIEnv *, jclass, jmethodID, va_list) = nullptr;
jint (*origCallStaticIntMethodA)(JNIEnv *, jclass, jmethodID, const jvalue *) = nullptr;

bool isObb(jmethodID method) {
    return method == reinterpret_cast<jmethodID>(&kObbMarker);
}

bool isStub(jmethodID method) {
    return method == reinterpret_cast<jmethodID>(&kStubMarker);
}

void writeSlot(void *slot, void *value) {
    const long page = sysconf(_SC_PAGESIZE);
    auto start = reinterpret_cast<uintptr_t>(slot) & ~(static_cast<uintptr_t>(page) - 1);
    if (mprotect(reinterpret_cast<void *>(start), static_cast<size_t>(page), PROT_READ | PROT_WRITE) != 0) {
        ALOGD("ObbMethodHook: mprotect failed");
        return;
    }
    *reinterpret_cast<void **>(slot) = value;
}

jmethodID missing(JNIEnv *env, const char *name, const char *sig, bool obb) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    ALOGD("ObbMethodHook: missing %s%s", name == nullptr ? "?" : name, sig == nullptr ? "" : sig);
    return reinterpret_cast<jmethodID>(obb ? &kObbMarker : &kStubMarker);
}

jstring obbPath(JNIEnv *env, jobject activity) {
    jclass type = env->GetObjectClass(activity);
    jmethodID getObbDir = origGetMethodID(env, type, "getObbDir", "()Ljava/io/File;");
    env->DeleteLocalRef(type);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (getObbDir == nullptr) return env->NewStringUTF("");
    jobject file = env->CallObjectMethod(activity, getObbDir);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (file == nullptr) return env->NewStringUTF("");
    jclass fileType = env->GetObjectClass(file);
    jmethodID absolute = origGetMethodID(env, fileType, "getAbsolutePath", "()Ljava/lang/String;");
    env->DeleteLocalRef(fileType);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (absolute == nullptr) {
        env->DeleteLocalRef(file);
        return env->NewStringUTF("");
    }
    auto path = static_cast<jstring>(env->CallObjectMethod(file, absolute));
    env->DeleteLocalRef(file);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (path == nullptr) return env->NewStringUTF("");
    const char *utf = env->GetStringUTFChars(path, nullptr);
    ALOGD("ObbMethodHook: GetObbFolder -> %s", utf == nullptr ? "" : utf);
    if (utf != nullptr) env->ReleaseStringUTFChars(path, utf);
    return path;
}

jmethodID hookGetMethodID(JNIEnv *env, jclass clazz, const char *name, const char *sig) {
    jmethodID method = origGetMethodID(env, clazz, name, sig);
    if (method != nullptr) return method;
    const bool obb = name != nullptr && sig != nullptr
            && std::strcmp(name, "GetObbFolder") == 0
            && std::strcmp(sig, "()Ljava/lang/String;") == 0;
    if (!env->ExceptionCheck() && !obb) return nullptr;
    return missing(env, name, sig, obb);
}

jmethodID hookGetStaticMethodID(JNIEnv *env, jclass clazz, const char *name, const char *sig) {
    jmethodID method = origGetStaticMethodID(env, clazz, name, sig);
    if (method != nullptr || !env->ExceptionCheck()) return method;
    return missing(env, name, sig, false);
}

jobject hookCallObjectMethod(JNIEnv *env, jobject obj, jmethodID method, ...) {
    if (isObb(method)) return obbPath(env, obj);
    if (isStub(method)) return nullptr;
    va_list args;
    va_start(args, method);
    jobject result = origCallObjectMethodV(env, obj, method, args);
    va_end(args);
    return result;
}

jobject hookCallObjectMethodV(JNIEnv *env, jobject obj, jmethodID method, va_list args) {
    if (isObb(method)) return obbPath(env, obj);
    if (isStub(method)) return nullptr;
    return origCallObjectMethodV(env, obj, method, args);
}

jobject hookCallObjectMethodA(JNIEnv *env, jobject obj, jmethodID method, const jvalue *args) {
    if (isObb(method)) return obbPath(env, obj);
    if (isStub(method)) return nullptr;
    return origCallObjectMethodA(env, obj, method, args);
}

jobject hookCallStaticObjectMethod(JNIEnv *env, jclass clazz, jmethodID method, ...) {
    if (isStub(method)) return nullptr;
    va_list args;
    va_start(args, method);
    jobject result = origCallStaticObjectMethodV(env, clazz, method, args);
    va_end(args);
    return result;
}

jobject hookCallStaticObjectMethodV(JNIEnv *env, jclass clazz, jmethodID method, va_list args) {
    if (isStub(method)) return nullptr;
    return origCallStaticObjectMethodV(env, clazz, method, args);
}

jobject hookCallStaticObjectMethodA(JNIEnv *env, jclass clazz, jmethodID method, const jvalue *args) {
    if (isStub(method)) return nullptr;
    return origCallStaticObjectMethodA(env, clazz, method, args);
}

void hookCallVoidMethod(JNIEnv *env, jobject obj, jmethodID method, ...) {
    if (isStub(method)) return;
    va_list args;
    va_start(args, method);
    origCallVoidMethodV(env, obj, method, args);
    va_end(args);
}

void hookCallVoidMethodV(JNIEnv *env, jobject obj, jmethodID method, va_list args) {
    if (isStub(method)) return;
    origCallVoidMethodV(env, obj, method, args);
}

void hookCallVoidMethodA(JNIEnv *env, jobject obj, jmethodID method, const jvalue *args) {
    if (isStub(method)) return;
    origCallVoidMethodA(env, obj, method, args);
}

void hookCallStaticVoidMethod(JNIEnv *env, jclass clazz, jmethodID method, ...) {
    if (isStub(method)) return;
    va_list args;
    va_start(args, method);
    origCallStaticVoidMethodV(env, clazz, method, args);
    va_end(args);
}

void hookCallStaticVoidMethodV(JNIEnv *env, jclass clazz, jmethodID method, va_list args) {
    if (isStub(method)) return;
    origCallStaticVoidMethodV(env, clazz, method, args);
}

void hookCallStaticVoidMethodA(JNIEnv *env, jclass clazz, jmethodID method, const jvalue *args) {
    if (isStub(method)) return;
    origCallStaticVoidMethodA(env, clazz, method, args);
}

jboolean hookCallBooleanMethod(JNIEnv *env, jobject obj, jmethodID method, ...) {
    if (isStub(method)) return JNI_FALSE;
    va_list args;
    va_start(args, method);
    jboolean result = origCallBooleanMethodV(env, obj, method, args);
    va_end(args);
    return result;
}

jboolean hookCallBooleanMethodV(JNIEnv *env, jobject obj, jmethodID method, va_list args) {
    if (isStub(method)) return JNI_FALSE;
    return origCallBooleanMethodV(env, obj, method, args);
}

jboolean hookCallBooleanMethodA(JNIEnv *env, jobject obj, jmethodID method, const jvalue *args) {
    if (isStub(method)) return JNI_FALSE;
    return origCallBooleanMethodA(env, obj, method, args);
}

jboolean hookCallStaticBooleanMethod(JNIEnv *env, jclass clazz, jmethodID method, ...) {
    if (isStub(method)) return JNI_FALSE;
    va_list args;
    va_start(args, method);
    jboolean result = origCallStaticBooleanMethodV(env, clazz, method, args);
    va_end(args);
    return result;
}

jboolean hookCallStaticBooleanMethodV(JNIEnv *env, jclass clazz, jmethodID method, va_list args) {
    if (isStub(method)) return JNI_FALSE;
    return origCallStaticBooleanMethodV(env, clazz, method, args);
}

jboolean hookCallStaticBooleanMethodA(JNIEnv *env, jclass clazz, jmethodID method, const jvalue *args) {
    if (isStub(method)) return JNI_FALSE;
    return origCallStaticBooleanMethodA(env, clazz, method, args);
}

jint hookCallIntMethod(JNIEnv *env, jobject obj, jmethodID method, ...) {
    if (isStub(method)) return 0;
    va_list args;
    va_start(args, method);
    jint result = origCallIntMethodV(env, obj, method, args);
    va_end(args);
    return result;
}

jint hookCallIntMethodV(JNIEnv *env, jobject obj, jmethodID method, va_list args) {
    if (isStub(method)) return 0;
    return origCallIntMethodV(env, obj, method, args);
}

jint hookCallIntMethodA(JNIEnv *env, jobject obj, jmethodID method, const jvalue *args) {
    if (isStub(method)) return 0;
    return origCallIntMethodA(env, obj, method, args);
}

jint hookCallStaticIntMethod(JNIEnv *env, jclass clazz, jmethodID method, ...) {
    if (isStub(method)) return 0;
    va_list args;
    va_start(args, method);
    jint result = origCallStaticIntMethodV(env, clazz, method, args);
    va_end(args);
    return result;
}

jint hookCallStaticIntMethodV(JNIEnv *env, jclass clazz, jmethodID method, va_list args) {
    if (isStub(method)) return 0;
    return origCallStaticIntMethodV(env, clazz, method, args);
}

jint hookCallStaticIntMethodA(JNIEnv *env, jclass clazz, jmethodID method, const jvalue *args) {
    if (isStub(method)) return 0;
    return origCallStaticIntMethodA(env, clazz, method, args);
}

}  // namespace

void ObbMethodHook::init(JNIEnv *env) {
    if (env == nullptr || env->functions == nullptr) return;
    auto *table = const_cast<JNINativeInterface *>(env->functions);
    origGetMethodID = table->GetMethodID;
    origGetStaticMethodID = table->GetStaticMethodID;
    origCallObjectMethodV = table->CallObjectMethodV;
    origCallObjectMethodA = table->CallObjectMethodA;
    origCallStaticObjectMethodV = table->CallStaticObjectMethodV;
    origCallStaticObjectMethodA = table->CallStaticObjectMethodA;
    origCallVoidMethodV = table->CallVoidMethodV;
    origCallVoidMethodA = table->CallVoidMethodA;
    origCallStaticVoidMethodV = table->CallStaticVoidMethodV;
    origCallStaticVoidMethodA = table->CallStaticVoidMethodA;
    origCallBooleanMethodV = table->CallBooleanMethodV;
    origCallBooleanMethodA = table->CallBooleanMethodA;
    origCallStaticBooleanMethodV = table->CallStaticBooleanMethodV;
    origCallStaticBooleanMethodA = table->CallStaticBooleanMethodA;
    origCallIntMethodV = table->CallIntMethodV;
    origCallIntMethodA = table->CallIntMethodA;
    origCallStaticIntMethodV = table->CallStaticIntMethodV;
    origCallStaticIntMethodA = table->CallStaticIntMethodA;

    writeSlot(reinterpret_cast<void *>(&table->GetMethodID), reinterpret_cast<void *>(hookGetMethodID));
    writeSlot(reinterpret_cast<void *>(&table->GetStaticMethodID), reinterpret_cast<void *>(hookGetStaticMethodID));
    writeSlot(reinterpret_cast<void *>(&table->CallObjectMethod), reinterpret_cast<void *>(hookCallObjectMethod));
    writeSlot(reinterpret_cast<void *>(&table->CallObjectMethodV), reinterpret_cast<void *>(hookCallObjectMethodV));
    writeSlot(reinterpret_cast<void *>(&table->CallObjectMethodA), reinterpret_cast<void *>(hookCallObjectMethodA));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticObjectMethod), reinterpret_cast<void *>(hookCallStaticObjectMethod));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticObjectMethodV), reinterpret_cast<void *>(hookCallStaticObjectMethodV));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticObjectMethodA), reinterpret_cast<void *>(hookCallStaticObjectMethodA));
    writeSlot(reinterpret_cast<void *>(&table->CallVoidMethod), reinterpret_cast<void *>(hookCallVoidMethod));
    writeSlot(reinterpret_cast<void *>(&table->CallVoidMethodV), reinterpret_cast<void *>(hookCallVoidMethodV));
    writeSlot(reinterpret_cast<void *>(&table->CallVoidMethodA), reinterpret_cast<void *>(hookCallVoidMethodA));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticVoidMethod), reinterpret_cast<void *>(hookCallStaticVoidMethod));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticVoidMethodV), reinterpret_cast<void *>(hookCallStaticVoidMethodV));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticVoidMethodA), reinterpret_cast<void *>(hookCallStaticVoidMethodA));
    writeSlot(reinterpret_cast<void *>(&table->CallBooleanMethod), reinterpret_cast<void *>(hookCallBooleanMethod));
    writeSlot(reinterpret_cast<void *>(&table->CallBooleanMethodV), reinterpret_cast<void *>(hookCallBooleanMethodV));
    writeSlot(reinterpret_cast<void *>(&table->CallBooleanMethodA), reinterpret_cast<void *>(hookCallBooleanMethodA));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticBooleanMethod), reinterpret_cast<void *>(hookCallStaticBooleanMethod));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticBooleanMethodV), reinterpret_cast<void *>(hookCallStaticBooleanMethodV));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticBooleanMethodA), reinterpret_cast<void *>(hookCallStaticBooleanMethodA));
    writeSlot(reinterpret_cast<void *>(&table->CallIntMethod), reinterpret_cast<void *>(hookCallIntMethod));
    writeSlot(reinterpret_cast<void *>(&table->CallIntMethodV), reinterpret_cast<void *>(hookCallIntMethodV));
    writeSlot(reinterpret_cast<void *>(&table->CallIntMethodA), reinterpret_cast<void *>(hookCallIntMethodA));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticIntMethod), reinterpret_cast<void *>(hookCallStaticIntMethod));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticIntMethodV), reinterpret_cast<void *>(hookCallStaticIntMethodV));
    writeSlot(reinterpret_cast<void *>(&table->CallStaticIntMethodA), reinterpret_cast<void *>(hookCallStaticIntMethodA));
    ALOGD("ObbMethodHook: installed");
}
