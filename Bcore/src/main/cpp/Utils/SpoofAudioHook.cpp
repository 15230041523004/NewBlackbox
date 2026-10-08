#include "SpoofAudioHook.h"

#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <pthread.h>
#include <sys/stat.h>
#include <unistd.h>
#include <string>
#include <vector>

#include "Dobby/dobby.h"
#include "xdl.h"

#define SPOOF_AUDIO_LOG(...) __android_log_print(ANDROID_LOG_INFO, "SpoofAudio", __VA_ARGS__)

namespace {

struct CbBox {
    void *callback;
    void *user;
};

pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
std::vector<int16_t> g_samples;
int g_channels = 1;
int g_rate = 44100;
size_t g_frame = 0;
std::string g_path;
time_t g_mtime = 0;
off_t g_size = 0;
std::atomic<int> g_on{0};
std::atomic<int> g_java_pcm{0};
std::atomic<uint32_t> g_tick{0};
int g_installed = 0;

using SetDataCallback = int32_t (*)(void *builder, void *callback, void *user_data);
using DataCallback = int32_t (*)(void *stream, void *user_data, void *audio, int32_t frames);
using StreamInt = int32_t (*)(void *stream);

SetDataCallback g_orig_set_callback = nullptr;
StreamInt g_format = nullptr;
StreamInt g_channel_count = nullptr;

int32_t LeInt(const uint8_t *p) {
    return (int32_t) (p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24));
}

int LeShort(const uint8_t *p) {
    return p[0] | (p[1] << 8);
}

void LoadWav(const char *path) {
    std::vector<int16_t> samples;
    int channels = 1;
    int rate = 44100;
    FILE *file = fopen(path, "rb");
    if (file != nullptr) {
        if (fseek(file, 0, SEEK_END) == 0) {
            long length = ftell(file);
            if (length > 44 && length <= 16 * 1024 * 1024 && fseek(file, 0, SEEK_SET) == 0) {
                std::vector<uint8_t> data((size_t) length);
                size_t got = fread(data.data(), 1, data.size(), file);
                if (got > 44 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') {
                    size_t pos = 12;
                    size_t data_off = 44;
                    size_t data_size = got > 44 ? got - 44 : 0;
                    while (pos + 8 <= got) {
                        int size = LeInt(data.data() + pos + 4);
                        if (size < 0) break;
                        bool fmt = data[pos] == 'f' && data[pos + 1] == 'm' && data[pos + 2] == 't';
                        bool chunk = data[pos] == 'd' && data[pos + 1] == 'a' && data[pos + 2] == 't' && data[pos + 3] == 'a';
                        if (fmt && size >= 16 && pos + 22 < got) {
                            channels = LeShort(data.data() + pos + 10);
                            if (channels < 1 || channels > 8) channels = 1;
                            int sample_rate = LeInt(data.data() + pos + 12);
                            if (sample_rate >= 8000 && sample_rate <= 192000) rate = sample_rate;
                        } else if (chunk) {
                            data_off = pos + 8;
                            data_size = (size_t) size;
                            break;
                        }
                        size_t step = 8u + (size_t) size + ((size_t) size & 1u);
                        if (step == 0) break;
                        pos += step;
                    }
                    if (data_off < got) {
                        if (data_off + data_size > got) data_size = got - data_off;
                        data_size &= ~size_t{1};
                        size_t count = data_size / 2;
                        samples.resize(count);
                        memcpy(samples.data(), data.data() + data_off, count * 2);
                    }
                }
            }
        }
        fclose(file);
    }
    pthread_mutex_lock(&g_mu);
    g_samples.swap(samples);
    g_channels = channels;
    g_rate = rate;
    g_frame = 0;
    pthread_mutex_unlock(&g_mu);
    struct stat st{};
    if (path != nullptr && stat(path, &st) == 0) {
        g_mtime = st.st_mtime;
        g_size = st.st_size;
    }
    SPOOF_AUDIO_LOG("pcm %u samples %d Hz x%d", (unsigned) g_samples.size(), g_rate, g_channels);
}

void MaybeReload() {
    if (g_java_pcm.load(std::memory_order_acquire)) return;
    uint32_t tick = g_tick.fetch_add(1, std::memory_order_relaxed);
    if ((tick & 31u) != 0 || g_path.empty()) return;
    struct stat st{};
    if (stat(g_path.c_str(), &st) != 0) return;
    if (st.st_mtime == g_mtime && st.st_size == g_size) return;
    LoadWav(g_path.c_str());
}

void CopyFakeBytes(void *buffer, size_t size) {
    MaybeReload();
    auto *out = static_cast<uint8_t *>(buffer);
    pthread_mutex_lock(&g_mu);
    if (g_samples.empty()) {
        memset(out, 0, size);
    } else {
        const auto *bytes = reinterpret_cast<const uint8_t *>(g_samples.data());
        size_t total = g_samples.size() * 2;
        size_t pos = (g_frame * 2) % total;
        for (size_t i = 0; i < size; i++) {
            out[i] = bytes[pos];
            pos++;
            if (pos >= total) pos = 0;
        }
        g_frame = pos / 2;
    }
    pthread_mutex_unlock(&g_mu);
}

ssize_t SpoofAudioRead(void *self, void *buffer, size_t size, bool blocking) {
    (void) self;
    (void) blocking;
    // Voice reads are a few kilobytes. A wild size means this symbol is not
    // the byte-count read, so leave the caller buffer alone.
    if (buffer == nullptr || size == 0 || size > 256u * 1024u) return 0;
    CopyFakeBytes(buffer, size);
    return static_cast<ssize_t>(size);
}

ssize_t SpoofAudioReadTimeout(void *self, void *buffer, size_t size, bool blocking, int64_t timeout) {
    (void) timeout;
    return SpoofAudioRead(self, buffer, size, blocking);
}

void FillFrames(void *buffer, int32_t frames, int channels, bool as_float) {
    MaybeReload();
    if (channels < 1) channels = 1;
    if (channels > 8) channels = 8;
    pthread_mutex_lock(&g_mu);
    int src_channels = g_channels > 0 ? g_channels : 1;
    size_t src_frames = g_samples.empty() ? 0 : g_samples.size() / (size_t) src_channels;
    auto *out = static_cast<uint8_t *>(buffer);
    for (int32_t i = 0; i < frames; i++) {
        int16_t frame[8];
        memset(frame, 0, sizeof(frame));
        if (src_frames > 0) {
            size_t index = (g_frame % src_frames) * (size_t) src_channels;
            for (int c = 0; c < src_channels && c < 8; c++) frame[c] = g_samples[index + c];
            g_frame++;
        }
        for (int c = 0; c < channels; c++) {
            int16_t sample = frame[c < src_channels ? c : 0];
            if (as_float) {
                float value = sample / 32768.0f;
                memcpy(out, &value, sizeof(value));
                out += sizeof(value);
            } else {
                memcpy(out, &sample, sizeof(sample));
                out += sizeof(sample);
            }
        }
    }
    pthread_mutex_unlock(&g_mu);
}

int32_t SpoofAAudioRead(void *stream, void *buffer, int32_t frames, int64_t timeout) {
    (void) timeout;
    if (buffer == nullptr || frames <= 0) return 0;
    int format = g_format != nullptr ? g_format(stream) : 1;
    int channels = g_channel_count != nullptr ? g_channel_count(stream) : 1;
    FillFrames(buffer, frames, channels, format == 2);
    return frames;
}

int32_t SpoofDataCallback(void *stream, void *user_data, void *audio, int32_t frames) {
    auto *box = static_cast<CbBox *>(user_data);
    int format = g_format != nullptr ? g_format(stream) : 1;
    int channels = g_channel_count != nullptr ? g_channel_count(stream) : 1;
    if (audio != nullptr && frames > 0) FillFrames(audio, frames, channels, format == 2);
    if (box != nullptr && box->callback != nullptr) {
        return reinterpret_cast<DataCallback>(box->callback)(stream, box->user, audio, frames);
    }
    return 0;
}

int32_t SpoofSetDataCallback(void *builder, void *callback, void *user_data) {
    if (g_orig_set_callback == nullptr) return -2;
    if (!g_on.load(std::memory_order_acquire) || callback == nullptr) {
        return g_orig_set_callback(builder, callback, user_data);
    }
    auto *box = static_cast<CbBox *>(malloc(sizeof(CbBox)));
    if (box == nullptr) return g_orig_set_callback(builder, callback, user_data);
    box->callback = callback;
    box->user = user_data;
    return g_orig_set_callback(builder, reinterpret_cast<void *>(SpoofDataCallback), box);
}

void *FindSymbol(void *handle, const char *name) {
    if (handle == nullptr) return nullptr;
    void *symbol = xdl_sym(handle, name, nullptr);
    if (symbol == nullptr) symbol = xdl_dsym(handle, name, nullptr);
    return symbol;
}

int HookSymbol(void *handle, const char *name, void *replacement) {
    void *symbol = FindSymbol(handle, name);
    if (symbol == nullptr) {
        SPOOF_AUDIO_LOG("miss %s", name);
        return 0;
    }
    void *origin = nullptr;
    if (DobbyHook(symbol, replacement, &origin) != 0) {
        SPOOF_AUDIO_LOG("dobby fail %s", name);
        return 0;
    }
    SPOOF_AUDIO_LOG("hooked %s", name);
    return 1;
}

}  // namespace

void UsePcm(JNIEnv *env, jbyteArray pcm, jint rate, jint channels) {
    if (pcm == nullptr) return;
    jsize n = env->GetArrayLength(pcm);
    if (n < 2) return;
    jsize bytes = n - (n & 1);
    std::vector<int16_t> samples((size_t) bytes / 2);
    env->GetByteArrayRegion(pcm, 0, bytes, reinterpret_cast<jbyte *>(samples.data()));
    pthread_mutex_lock(&g_mu);
    g_samples.swap(samples);
    g_channels = channels >= 1 && channels <= 8 ? channels : 1;
    g_rate = rate >= 8000 && rate <= 192000 ? rate : 44100;
    g_frame = 0;
    pthread_mutex_unlock(&g_mu);
    g_java_pcm.store(1, std::memory_order_release);
    SPOOF_AUDIO_LOG("pcm from java %u samples %d Hz x%d", (unsigned) (bytes / 2), g_rate, g_channels);
}

jboolean enableMicTap(JNIEnv *env, jclass, jstring path, jbyteArray pcm, jint rate, jint channels) {
    if (g_installed) return g_installed > 0 ? JNI_TRUE : JNI_FALSE;
    const char *chars = nullptr;
    if (path != nullptr) chars = env->GetStringUTFChars(path, nullptr);
    if (chars != nullptr && chars[0] != '\0') g_path = chars;
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    UsePcm(env, pcm, rate, channels);
    if (g_samples.empty() && !g_path.empty()) LoadWav(g_path.c_str());
    if (g_samples.empty()) SPOOF_AUDIO_LOG("no pcm, microphone reads stay silent");

    g_on.store(1, std::memory_order_release);
    int hooked = 0;
    void *audio = xdl_open("libaudioclient.so", XDL_TRY_FORCE_LOAD);
    hooked += HookSymbol(audio, "_ZN7android11AudioRecord4readEPvmb",
                         reinterpret_cast<void *>(SpoofAudioRead));
    hooked += HookSymbol(audio, "_ZN7android11AudioRecord4readEPvjb",
                         reinterpret_cast<void *>(SpoofAudioRead));
    void *aaudio = xdl_open("libaaudio.so", XDL_TRY_FORCE_LOAD);
    g_format = reinterpret_cast<StreamInt>(FindSymbol(aaudio, "AAudioStream_getFormat"));
    g_channel_count = reinterpret_cast<StreamInt>(FindSymbol(aaudio, "AAudioStream_getChannelCount"));
    hooked += HookSymbol(aaudio, "AAudioStream_read", reinterpret_cast<void *>(SpoofAAudioRead));
    void *set_callback = FindSymbol(aaudio, "AAudioStreamBuilder_setDataCallback");
    if (set_callback != nullptr && DobbyHook(set_callback, reinterpret_cast<void *>(SpoofSetDataCallback),
                                             reinterpret_cast<void **>(&g_orig_set_callback)) == 0) {
        SPOOF_AUDIO_LOG("hooked AAudioStreamBuilder_setDataCallback");
        hooked++;
    } else {
        SPOOF_AUDIO_LOG("miss AAudioStreamBuilder_setDataCallback");
    }
    SPOOF_AUDIO_LOG("native mic symbols %d", hooked);
    g_installed = hooked > 0 ? 1 : -1;
    return hooked > 0 ? JNI_TRUE : JNI_FALSE;
}

static int32_t BlockStartPreview(void *self) {
    (void) self;
    return 0;
}

jboolean enableCameraBlock(JNIEnv *, jclass) {
    static int installed = 0;
    if (installed) return installed > 0 ? JNI_TRUE : JNI_FALSE;
    void *camera = xdl_open("libcamera_client.so", XDL_TRY_FORCE_LOAD);
    const char *name = "_ZN7android6Camera12startPreviewEv";
    void *symbol = nullptr;
    if (camera != nullptr) {
        symbol = xdl_sym(camera, name, nullptr);
        if (symbol == nullptr) symbol = xdl_dsym(camera, name, nullptr);
    }
    int hooked = 0;
    if (symbol == nullptr) {
        SPOOF_AUDIO_LOG("miss %s", name);
    } else {
        void *origin = nullptr;
        if (DobbyHook(symbol, reinterpret_cast<void *>(BlockStartPreview), &origin) != 0) {
            SPOOF_AUDIO_LOG("dobby fail %s", name);
        } else {
            SPOOF_AUDIO_LOG("hooked %s", name);
            hooked = 1;
        }
    }
    installed = hooked > 0 ? 1 : -1;
    return hooked > 0 ? JNI_TRUE : JNI_FALSE;
}
