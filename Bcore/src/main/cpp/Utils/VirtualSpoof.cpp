#include <sys/system_properties.h>
#include <cstring>
#include "./xdl.h"
#include <android/log.h>
#include <dlfcn.h>
#include "Dobby/dobby.h"
#include "VirtualSpoof.h"
#include <atomic>
#include <cstdint>


#define LOG_TAG "VirtualSpoof"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

struct SpoofedProp {
    const char* key;
    const char* value;
};

SpoofedProp spoofed_props[] = {
        {"ro.product.model", "Pixel 6"},
        {"ro.product.brand", "google"},
        {"ro.product.manufacturer", "Google"},
        {"ro.product.device", "oriole"},
        {"ro.build.fingerprint", "google/oriole/oriole:12/SP1A.210812.015/7679548:user/release-keys"},
        {"ro.build.version.release", "12"},
        {"ro.build.version.security_patch", "2022-01-05"},
        {"ro.serialno", "1A2B3C4D5E6F"},
        {"ro.hardware", "qcom"},
        {"ro.boot.hardware", "qcom"},
        {"ro.product.board", "lahaina"},
        {"ro.product.cpu.abi", "arm64-v8a"},
        {"ro.kernel.qemu", "0"},
        {"ro.kernel.android.qemud", ""},
        {"ro.hardware.egl", "adreno"},
        {"ro.boot.qemu", "0"},
    {nullptr, nullptr} 
};


static std::atomic<bool> hide_guest_adb{false};

void set_guest_adb_hidden(bool enabled) {
    hide_guest_adb.store(enabled, std::memory_order_release);
}

static const SpoofedProp debug_props[] = {
        {"ro.debuggable", "0"}, {"ro.secure", "1"}, {"ro.adb.secure", "1"},
        {"ro.build.type", "user"}, {"ro.build.tags", "release-keys"},
        {"sys.usb.config", "mtp"}, {"persist.sys.usb.config", "mtp"},
        {"sys.usb.state", "mtp"}, {"init.svc.adbd", "stopped"},
        {"service.adb.tcp.port", "-1"}, {"persist.adb.tcp.port", "-1"},
        {nullptr, nullptr}
};

static const char* override_property(const char* name, bool include_legacy_device_spoof = true) {
    if (!name) return nullptr;
    if (hide_guest_adb.load(std::memory_order_acquire)) {
        for (const auto& prop : debug_props) {
            if (!prop.key) break;
            if (strcmp(name, prop.key) == 0) return prop.value;
        }
    }
    if (!include_legacy_device_spoof) return nullptr;
    for (int i = 0; spoofed_props[i].key; ++i) {
        if (strcmp(name, spoofed_props[i].key) == 0) return spoofed_props[i].value;
    }
    return nullptr;
}

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;
static const prop_info* (*orig_system_property_find)(const char*) = nullptr;
static int (*orig_system_property_read)(const prop_info*, char*, char*) = nullptr;
using PropertyCallback = void (*)(void*, const char*, const char*, uint32_t);
static void (*orig_system_property_read_callback)(const prop_info*, PropertyCallback, void*) = nullptr;
static uint32_t (*orig_system_property_serial)(const prop_info*) = nullptr;
static bool (*orig_system_property_wait)(const prop_info*, uint32_t, uint32_t*, const timespec*) = nullptr;

// Some OEM properties cannot be found by an app UID. Keep opaque, stable handles
// for those keys and handle them at the public property API boundaries.
static const SpoofedProp* virtual_property(const prop_info* info) {
    for (const auto& prop : debug_props) {
        if (!prop.key) break;
        if (info == reinterpret_cast<const prop_info*>(&prop)) return &prop;
    }
    return nullptr;
}

static const prop_info* my_system_property_find(const char* name) {
    const prop_info* original = orig_system_property_find(name);
    if (original || !hide_guest_adb.load(std::memory_order_acquire)) return original;
    for (const auto& prop : debug_props) {
        if (!prop.key) break;
        if (strcmp(name, prop.key) == 0) return reinterpret_cast<const prop_info*>(&prop);
    }
    return nullptr;
}

static const char* virtual_value(const SpoofedProp* prop) {
    return hide_guest_adb.load(std::memory_order_acquire) ? prop->value : "";
}


int my_system_property_get(const char *name, char *value) {
    if (const char* replacement = override_property(name)) {
        strcpy(value, replacement);
        return strlen(value);
    }
    if (orig_system_property_get) {
        return orig_system_property_get(name, value);
    }
    value[0] = '\0';
    return 0;
}

static int my_system_property_read(const prop_info* info, char* name, char* value) {
    if (const auto* prop = virtual_property(info)) {
        if (name) strcpy(name, prop->key);
        const char* replacement = virtual_value(prop);
        strcpy(value, replacement);
        return strlen(replacement);
    }
    char local_name[256] = {};
    char* property_name = name ? name : local_name;
    int length = orig_system_property_read(info, property_name, value);
    if (const char* replacement = override_property(property_name, false)) {
        strcpy(value, replacement);
        return strlen(value);
    }
    return length;
}

struct CallbackContext { PropertyCallback callback; void* cookie; };

static void substitute_callback(void* cookie, const char* name, const char* value, uint32_t serial) {
    auto* context = static_cast<CallbackContext*>(cookie);
    if (const char* replacement = override_property(name, false)) {
        value = replacement;
        serial = (serial & 0x00ffffffU) | (static_cast<uint32_t>(strlen(value)) << 24U);
    }
    context->callback(context->cookie, name, value, serial);
}

static void my_system_property_read_callback(const prop_info* info, PropertyCallback callback, void* cookie) {
    if (const auto* prop = virtual_property(info)) {
        const char* value = virtual_value(prop);
        callback(cookie, prop->key, value, static_cast<uint32_t>(strlen(value)) << 24U);
        return;
    }
    CallbackContext context{callback, cookie};
    orig_system_property_read_callback(info, substitute_callback, &context);
}

static uint32_t my_system_property_serial(const prop_info* info) {
    if (const auto* prop = virtual_property(info)) return static_cast<uint32_t>(strlen(virtual_value(prop))) << 24U;
    return orig_system_property_serial(info);
}

static bool my_system_property_wait(const prop_info* info, uint32_t old_serial, uint32_t* new_serial,
                                    const timespec* timeout) {
    if (const auto* prop = virtual_property(info)) {
        uint32_t serial = my_system_property_serial(info);
        if (new_serial) *new_serial = serial;
        return serial != old_serial;
    }
    return orig_system_property_wait(info, old_serial, new_serial, timeout);
}

void install_property_get_hook() {
    void* handle = xdl_open("libc.so", XDL_DEFAULT);
    void* target = xdl_dsym(handle, "__system_property_get", nullptr);
    if (target) {
        if (DobbyHook(target, (void*)my_system_property_get, (void**)&orig_system_property_get) == 0) {
            LOGD("Spoof installed successfully");
        } else {
            LOGD("Spoof hook failed");
        }
    }
    target = xdl_dsym(handle, "__system_property_read", nullptr);
    bool read_ok = target && DobbyHook(target, (void*)my_system_property_read,
                            (void**)&orig_system_property_read) == 0;
    if (!read_ok) LOGD("Property read hook unavailable");
    // Java SystemProperties and cached prop_info handles use this API on modern Android.
    target = xdl_dsym(handle, "__system_property_read_callback", nullptr);
    bool callback_ok = target && DobbyHook(target, (void*)my_system_property_read_callback,
                            (void**)&orig_system_property_read_callback) == 0;
    if (!callback_ok) LOGD("Property callback hook unavailable");
    target = xdl_dsym(handle, "__system_property_serial", nullptr);
    bool serial_ok = target && DobbyHook(target, (void*)my_system_property_serial,
                            (void**)&orig_system_property_serial) == 0;
    if (!serial_ok) LOGD("Property serial hook unavailable");
    target = xdl_dsym(handle, "__system_property_wait", nullptr);
    bool wait_ok = target && DobbyHook(target, (void*)my_system_property_wait,
                            (void**)&orig_system_property_wait) == 0;
    if (!wait_ok) LOGD("Property wait hook unavailable");
    // Never expose an opaque virtual handle unless every operation that accepts it is hooked.
    if (read_ok && callback_ok && serial_ok && wait_ok) {
        target = xdl_dsym(handle, "__system_property_find", nullptr);
        if (target && DobbyHook(target, (void*)my_system_property_find,
                                (void**)&orig_system_property_find) != 0) LOGD("Property find hook failed");
    }
    if (handle) xdl_close(handle);
}


__attribute__((constructor)) void init_virtual_spoof()
{
    install_property_get_hook();
    LOGD("VirtualSpoof: __system_property_get hook loaded");
}
