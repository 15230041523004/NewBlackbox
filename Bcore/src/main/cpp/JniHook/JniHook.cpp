



#include <jni.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>
#include "JniHook.h"
#include "Log.h"
#include "ArtMethod.h"

static struct {
    int api_level;
    unsigned int art_field_size;
    int art_field_flags_offset;

    unsigned int art_method_size;
    int art_method_flags_offset;
    int art_method_native_offset;
    int art_method_entry_offset;

    int class_flags_offset;

    jclass method_utils_class;
    jmethodID get_method_desc_id;
    jmethodID get_method_declaring_class_id;
    jmethodID get_method_name_id;

} HookEnv;

static const char *GetMethodDesc(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_desc_id,
                                                                      javaMethod));
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodDeclaringClass(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_declaring_class_id,
                                                                      javaMethod));
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodName(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_name_id,
                                                                      javaMethod));
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

inline static uint32_t GetAccessFlags(const char *art_method) {
    return *reinterpret_cast<const uint32_t *>(art_method + HookEnv.art_method_flags_offset);
}

inline static bool SetAccessFlags(char *art_method, uint32_t flags) {
    *reinterpret_cast<uint32_t *>(art_method + HookEnv.art_method_flags_offset) = flags;
    return true;
}

inline static bool AddAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag | flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool ClearAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag & ~flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool HasAccessFlag(char *art_method, uint32_t flag) {
    uint32_t flags = GetAccessFlags(art_method);
    ALOGD("AccessFlag:flags = 0x%x,flag = 0x%x",flags,flag);
    return (flags & flag) == flag;
}


inline static bool IsNativeMethod(char *art_method) {
    try {
        return HasAccessFlag(art_method, kAccNative);
    } catch (...) {
        ALOGD("NativeCore: Error checking native method flag, assuming not native");
        return false;
    }
}

inline static bool ClearFastNativeFlag(char *art_method) {
    
    return HookEnv.api_level < __ANDROID_API_P__ && ClearAccessFlag(art_method, kAccFastNative);
}

static void *GetArtMethod(JNIEnv *env, jclass clazz, jmethodID methodId) {
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
        jobject method = env->ToReflectedMethod(clazz, methodId, true);
        return reinterpret_cast<void *>(env->GetLongField(method, artId));
    } else {
        return methodId;
    }
}

static void *GetFieldMethod(JNIEnv *env, jobject field) {
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass fieldClass = env->FindClass("java/lang/reflect/Field");
        jmethodID getArtField = env->GetMethodID(fieldClass, "getArtField", "()J");
        return reinterpret_cast<void *>(env->CallLongMethod(field, getArtField));
    } else {
        return env->FromReflectedField(field);
    }
}

bool CheckFlags(void *artMethod) {
    char *method = static_cast<char *>(artMethod);
    
    
    try {
        if (!HasAccessFlag(method, kAccNative)) {
            ALOGD("Method is not native, skipping hook");
            return false;
        }
        ClearFastNativeFlag(method);
        return true;
    } catch (...) {
        ALOGD("Error checking method flags, assuming not native");
        return false;
    }
}

void JniHook::HookJniFun(JNIEnv *env, jobject java_method, void *new_fun,
                         void **orig_fun, bool is_static) {
    const char *class_name = GetMethodDeclaringClass(env, java_method);
    const char *method_name = GetMethodName(env, java_method);
    const char *sign = GetMethodDesc(env, java_method);
    HookJniFun(env, class_name, method_name, sign, new_fun, orig_fun, is_static);
}

void
JniHook::HookJniFun(JNIEnv *env, const char *class_name, const char *method_name, const char *sign,
                    void *new_fun, void **orig_fun, bool is_static) {
    if (HookEnv.art_method_native_offset == 0) {
        return;
    }
    jclass clazz = env->FindClass(class_name);
    if (!clazz) {
        ALOGD("findClass fail: %s %s", class_name, method_name);
        env->ExceptionClear();
        return;
    }
    jmethodID method = nullptr;
    if (is_static) {
        method = env->GetStaticMethodID(clazz, method_name, sign);
    } else {
        method = env->GetMethodID(clazz, method_name, sign);
    }
    if (!method) {
        env->ExceptionClear();
        ALOGD("get method id fail: %s %s", class_name, method_name);
        return;
    }
    JNINativeMethod gMethods[] = {
            {method_name, sign, (void *) new_fun},
    };

    auto artMethod = reinterpret_cast<uintptr_t *>(GetArtMethod(env, clazz, method));
    if (!CheckFlags(artMethod)) {
        ALOGD("Skipping hook for non-native method: %s.%s", class_name, method_name);
        return;
    }
    *orig_fun = reinterpret_cast<void *>(artMethod[HookEnv.art_method_native_offset]);
    if (env->RegisterNatives(clazz, gMethods, 1) < 0) {
        ALOGE("jni hook error. class：%s, method：%s", class_name, method_name);
        return;
    }
    
    if (HookEnv.api_level == __ANDROID_API_O__ || HookEnv.api_level == __ANDROID_API_O_MR1__) {
        AddAccessFlag((char *) artMethod, kAccFastNative);
    }
    ALOGD("register class：%s, method：%s success!", class_name, method_name);
}

__attribute__((section (".mytext")))  JNICALL void native_offset
        (JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext")))  JNICALL void native_offset2
        (JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext")))  JNICALL void set_method_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject method) {
    jmethodID methodId = env->FromReflectedMethod(method);
    char *art_method = static_cast<char *>(GetArtMethod(env, clazz, methodId));
    AddAccessFlag(art_method, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(art_method, kAccPublicApi);
    }
}

__attribute__((section (".mytext")))  JNICALL void set_field_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject field) {
    char *artField = static_cast<char *>(GetFieldMethod(env, field));
    AddAccessFlag(artField, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(artField, kAccPublicApi);
    }
    ClearAccessFlag(artField, kAccFinal);
}

static void *ArtMethodPointer(JNIEnv *env, jobject reflectedMethod) {
    if (reflectedMethod == nullptr) return nullptr;
    if (HookEnv.api_level >= 26) {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        if (executable == nullptr) {
            env->ExceptionClear();
            return nullptr;
        }
        jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
        if (artId == nullptr) {
            env->ExceptionClear();
            return nullptr;
        }
        return reinterpret_cast<void *>(static_cast<uintptr_t>(
                env->GetLongField(reflectedMethod, artId)));
    }
    return env->FromReflectedMethod(reflectedMethod);
}

static pthread_mutex_t g_art_write_lock = PTHREAD_MUTEX_INITIALIZER;
static const char *g_art_write_how = nullptr;

static void NoteArtWrite(const char *how) {
    if (g_art_write_how == how) return;
    g_art_write_how = how;
    __android_log_print(ANDROID_LOG_INFO, "NativeCore", "copyArtMethod via %s", how);
}

static void ArtPageSpan(void *addr, size_t len, uintptr_t *start_out, size_t *span_out) {
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;
    uintptr_t start = reinterpret_cast<uintptr_t>(addr) & ~static_cast<uintptr_t>(page - 1);
    uintptr_t end = (reinterpret_cast<uintptr_t>(addr) + len + static_cast<uintptr_t>(page) - 1)
                    & ~static_cast<uintptr_t>(page - 1);
    *start_out = start;
    *span_out = end - start;
}

static bool MakeArtWritable(void *addr, size_t len) {
    uintptr_t start = 0;
    size_t span = 0;
    ArtPageSpan(addr, len, &start, &span);
    if (mprotect(reinterpret_cast<void *>(start), span, PROT_READ | PROT_WRITE) == 0) return true;
    return mprotect(reinterpret_cast<void *>(start), span,
                    PROT_READ | PROT_WRITE | PROT_EXEC) == 0;
}

// Boot-image ArtMethods are mapped from a read-only fd, so mprotect cannot add
// write permission. Replace those pages with a private writable copy.
static bool RemapArtWritable(void *addr, size_t len) {
    uintptr_t start = 0;
    size_t span = 0;
    ArtPageSpan(addr, len, &start, &span);
    void *backup = mmap(nullptr, span, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (backup == MAP_FAILED) {
        ALOGE("copyArtMethod: backup mmap errno=%d", errno);
        return false;
    }
    memcpy(backup, reinterpret_cast<void *>(start), span);
    int prots[2] = {PROT_READ | PROT_WRITE, PROT_READ | PROT_WRITE | PROT_EXEC};
    void *mapped = MAP_FAILED;
    int saved = 0;
    for (int prot : prots) {
        mapped = mmap(reinterpret_cast<void *>(start), span, prot,
                      MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
        if (mapped != MAP_FAILED) break;
        saved = errno;
    }
    if (mapped == MAP_FAILED) {
        ALOGE("copyArtMethod: remap errno=%d", saved);
        munmap(backup, span);
        return false;
    }
    memcpy(mapped, backup, span);
    munmap(backup, span);
    return true;
}

static bool CommitArtBytes(void *addr, const void *data, size_t len, const char *how) {
    memcpy(addr, data, len);
    if (memcmp(addr, data, len) != 0) return false;
    __builtin___clear_cache(reinterpret_cast<char *>(addr),
                            reinterpret_cast<char *>(addr) + len);
    NoteArtWrite(how);
    return true;
}

static bool WriteArtBytes(void *addr, const void *data, size_t len) {
    pthread_mutex_lock(&g_art_write_lock);
    bool wrote = false;
    // process_vm_writev keeps the boot-image mapping. Remap replaces that page
    // with anonymous memory, and the GC then treats ArtMethod words as objects.
    if (MakeArtWritable(addr, len) && CommitArtBytes(addr, data, len, "mprotect")) {
        wrote = true;
    }
#if defined(__NR_process_vm_writev)
    if (!wrote) {
        struct iovec local{};
        struct iovec remote{};
        local.iov_base = const_cast<void *>(data);
        local.iov_len = len;
        remote.iov_base = addr;
        remote.iov_len = len;
        if (syscall(__NR_process_vm_writev, getpid(), &local, 1, &remote, 1, 0) == static_cast<ssize_t>(len)
            && memcmp(addr, data, len) == 0) {
            __builtin___clear_cache(reinterpret_cast<char *>(addr),
                                    reinterpret_cast<char *>(addr) + len);
            NoteArtWrite("process_vm_writev");
            wrote = true;
        }
    }
#endif
    if (!wrote) {
        int fd = open("/proc/self/mem", O_RDWR | O_CLOEXEC);
        if (fd >= 0) {
            ssize_t n = pwrite(fd, data, len, static_cast<off_t>(reinterpret_cast<uintptr_t>(addr)));
            close(fd);
            if (n == static_cast<ssize_t>(len) && memcmp(addr, data, len) == 0) {
                __builtin___clear_cache(reinterpret_cast<char *>(addr),
                                        reinterpret_cast<char *>(addr) + len);
                NoteArtWrite("proc_mem");
                wrote = true;
            }
        }
    }
    if (!wrote && RemapArtWritable(addr, len) && CommitArtBytes(addr, data, len, "remap")) {
        wrote = true;
    }
    if (!wrote) ALOGE("copyArtMethod: write failed errno=%d", errno);
    pthread_mutex_unlock(&g_art_write_lock);
    return wrote;
}

static void *g_stub_page = nullptr;
static size_t g_stub_used = 0;
static bool g_stub_rwx = false;
static const size_t kStubPage = 4096;

static void *AllocStub(size_t bytes) {
    if (g_stub_page == nullptr || g_stub_used + bytes > kStubPage) {
        void *page = mmap(nullptr, kStubPage, PROT_READ | PROT_WRITE | PROT_EXEC,
                          MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        g_stub_rwx = page != MAP_FAILED;
        if (!g_stub_rwx) {
            page = mmap(nullptr, kStubPage, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (page == MAP_FAILED) return nullptr;
        }
        g_stub_page = page;
        g_stub_used = 0;
    }
    if (!g_stub_rwx && mprotect(g_stub_page, kStubPage, PROT_READ | PROT_WRITE) != 0) {
        return nullptr;
    }
    void *stub = reinterpret_cast<char *>(g_stub_page) + g_stub_used;
    g_stub_used += bytes;
    return stub;
}

static bool SealStub(void *stub, size_t bytes) {
    __builtin___clear_cache(reinterpret_cast<char *>(stub),
                            reinterpret_cast<char *>(stub) + bytes);
    if (g_stub_rwx) return true;
    return mprotect(g_stub_page, kStubPage, PROT_READ | PROT_EXEC) == 0;
}

// Jump to the replacement method with its own ArtMethod in the method register.
// The target keeps its declaring class, which the GC scans. A full copy of an
// app method onto a boot-image method makes that scan mark a garbage word.
static void *BuildTrampoline(void *art_method, void *entry) {
#if defined(__aarch64__)
    struct Stub {
        uint32_t code[4];
        void *method;
        void *entry;
    };
    static_assert(sizeof(Stub) == 32, "arm64 stub");
    Stub *stub = reinterpret_cast<Stub *>(AllocStub(sizeof(Stub)));
    if (stub == nullptr) return nullptr;
    stub->code[0] = 0x58000080;  // ldr x0, #16
    stub->code[1] = 0x580000B0;  // ldr x16, #20
    stub->code[2] = 0xD61F0200;  // br x16
    stub->code[3] = 0xD503201F;  // nop
    stub->method = art_method;
    stub->entry = entry;
    if (!SealStub(stub, sizeof(Stub))) return nullptr;
    return stub;
#elif defined(__arm__)
    struct Stub {
        uint32_t code[2];
        void *method;
        void *entry;
    };
    Stub *stub = reinterpret_cast<Stub *>(AllocStub(sizeof(Stub)));
    if (stub == nullptr) return nullptr;
    stub->code[0] = 0xE59F0000;  // ldr r0, [pc, #0]
    stub->code[1] = 0xE59FF000;  // ldr pc, [pc, #0]
    stub->method = art_method;
    stub->entry = entry;
    if (!SealStub(stub, sizeof(Stub))) return nullptr;
    return stub;
#else
    (void) art_method;
    (void) entry;
    return nullptr;
#endif
}

static jboolean RedirectArtMethod(JNIEnv *env, jclass, jobject src, jobject dst) {
    if (HookEnv.art_method_entry_offset <= 0) return JNI_FALSE;
    if (static_cast<unsigned int>(HookEnv.art_method_entry_offset) + sizeof(void *)
        > HookEnv.art_method_size) {
        return JNI_FALSE;
    }
    void *from = ArtMethodPointer(env, src);
    void *to = ArtMethodPointer(env, dst);
    if (from == nullptr || to == nullptr || from == to) return JNI_FALSE;
    void *entry = nullptr;
    memcpy(&entry, reinterpret_cast<char *>(from) + HookEnv.art_method_entry_offset, sizeof(entry));
    if (entry == nullptr) return JNI_FALSE;
    void *stub = BuildTrampoline(from, entry);
    if (stub == nullptr) {
        ALOGE("redirectArtMethod: trampoline failed");
        return JNI_FALSE;
    }
    char *target = reinterpret_cast<char *>(to);
    if (!WriteArtBytes(target + HookEnv.art_method_entry_offset, &stub, sizeof(stub))) {
        return JNI_FALSE;
    }
    if (HookEnv.art_method_flags_offset > 0) {
        uint32_t flags = 0;
        memcpy(&flags, target + HookEnv.art_method_flags_offset, sizeof(flags));
        flags |= kAccCompileDontBother;
        flags &= ~kAccNterpInvokeFastPathFlag;
        WriteArtBytes(target + HookEnv.art_method_flags_offset, &flags, sizeof(flags));
    }
    static bool logged = false;
    if (!logged) {
        logged = true;
        __android_log_print(ANDROID_LOG_INFO, "NativeCore", "ArtMethod redirect via trampoline");
    }
    return JNI_TRUE;
}

static jboolean CopyArtMethod(JNIEnv *env, jclass, jobject src, jobject dst) {
    if (HookEnv.art_method_size < 16 || HookEnv.art_method_size > 128) return JNI_FALSE;
    void *from = ArtMethodPointer(env, src);
    void *to = ArtMethodPointer(env, dst);
    if (from == nullptr || to == nullptr || from == to) return JNI_FALSE;
    // Full copy, used to save the original into the backup method.
    char buf[128];
    memcpy(buf, from, HookEnv.art_method_size);
    if (HookEnv.art_method_flags_offset > 0) {
        // Reflection invokes a public instance method through the receiver's
        // vtable. The saved copy has the original slot, now containing the hook,
        // so that dispatch recurses. A private copy is invoked directly.
        uint32_t flags = 0;
        memcpy(&flags, buf + HookEnv.art_method_flags_offset, sizeof(flags));
        flags &= ~(kAccPublic | kAccProtected | kAccNterpInvokeFastPathFlag);
        flags |= kAccPrivate | kAccCompileDontBother;
        memcpy(buf + HookEnv.art_method_flags_offset, &flags, sizeof(flags));
    }
    return WriteArtBytes(to, buf, HookEnv.art_method_size) ? JNI_TRUE : JNI_FALSE;
}

void registerNative(JNIEnv *env) {
    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    JNINativeMethod gMethods[] = {
            {"nativeOffset",  "()V",                                            (void *) native_offset},
            {"nativeOffset2", "()V",                                            (void *) native_offset2},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V", (void *) set_method_accessible},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V",  (void *) set_field_accessible},
            {"copyArtMethod", "(Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;)Z", (void *) CopyArtMethod},
            {"redirectArtMethod", "(Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;)Z", (void *) RedirectArtMethod},
    };
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        ALOGE("jni register error.");
    }
}

void JniHook::InitJniHook(JNIEnv *env, int api_level) {
    registerNative(env);
    HookEnv.api_level = api_level;

    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    jmethodID nativeOffsetId = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    jmethodID nativeOffset2Id = env->GetStaticMethodID(clazz, "nativeOffset2", "()V");

    jfieldID nativeOffsetFieldId = env->GetStaticFieldID(clazz, "NATIVE_OFFSET", "I");
    jfieldID nativeOffsetField2Id = env->GetStaticFieldID(clazz, "NATIVE_OFFSET_2", "I");

    void *nativeOffsetField = GetFieldMethod(env, env->ToReflectedField(clazz, nativeOffsetFieldId,
                                                                        true));
    void *nativeOffsetField2 = GetFieldMethod(env, env->ToReflectedField(clazz, nativeOffsetField2Id,
                                                                         true));
    HookEnv.art_field_size = (size_t) nativeOffsetField2 - (size_t) nativeOffsetField;

    void *nativeOffset = GetArtMethod(env, clazz, nativeOffsetId);
    void *nativeOffset2 = GetArtMethod(env, clazz, nativeOffset2Id);
    HookEnv.art_method_size = (size_t) nativeOffset2 - (size_t) nativeOffset;

    int i = 0;
    
    auto artMethod = reinterpret_cast<uintptr_t *>(nativeOffset);
    for (i = 0; i < HookEnv.art_method_size; ++i) {
        if (reinterpret_cast<void *>(artMethod[i]) == native_offset) {
            HookEnv.art_method_native_offset = i;
            break;
        }
    }
    if(i == HookEnv.art_method_size){
        ALOGE("init jni hook error. art_method_native_offset not found!");
        return;
    }
    HookEnv.art_method_entry_offset =
            (HookEnv.art_method_native_offset + 1) * static_cast<int>(sizeof(void *));

    uint32_t flags = 0x0;
    flags = flags | kAccPublic;
    flags = flags | kAccStatic;
    flags = flags | kAccNative;
    flags = flags | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) {
        flags = flags | kAccPublicApi;
    }
    if (api_level >= __ANDROID_API_S__) {
        flags = flags | kAccNterpInvokeFastPathFlag;
    }

    char *start = reinterpret_cast<char *>(artMethod);
    for (i = 1; i < HookEnv.art_method_size; ++i) {
        auto value = *(uint32_t *) (start + i * sizeof(uint32_t));

        if (value == flags) {
            HookEnv.art_method_flags_offset = i * sizeof(uint32_t);
            break;
        }
    }
    if(i == HookEnv.art_method_size){
        ALOGE("init jni hook error. art_method_flags_offset not found!");
        return;
    }

    flags = 0x0;
    flags = flags | kAccPublic;
    flags = flags | kAccStatic;
    flags = flags | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) {
        flags = flags | kAccPublicApi;
    }
    char *fieldStart = reinterpret_cast<char *>(nativeOffsetField);
    for (i = 1; i < HookEnv.art_field_size; ++i) {
        auto value = *(int32_t *) (fieldStart + i * sizeof(int32_t));
        if (value == flags) {
            HookEnv.art_field_flags_offset = i * sizeof(int32_t);
            break;
        }
    }
    if(i == HookEnv.art_field_size){
        ALOGE("init jni hook error. art_field_flags_offset not found!");
        return;
    }

    HookEnv.method_utils_class = env->FindClass("top/niunaijun/jnihook/MethodUtils");
    HookEnv.get_method_desc_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getDesc",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_declaring_class_id = env->GetStaticMethodID(HookEnv.method_utils_class,
                                                                   "getDeclaringClass",
                                                                   "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_name_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getMethodName",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
}

