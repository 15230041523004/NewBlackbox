#ifndef BLACKBOX_OBB_METHOD_HOOK_H
#define BLACKBOX_OBB_METHOD_HOOK_H

#include <jni.h>

class ObbMethodHook {
public:
    static void init(JNIEnv *env);
};

#endif
