#include "SpoofAudioHook.h"

#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <pthread.h>
#include <sys/stat.h>
#include <unistd.h>
#include <time.h>
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

enum MicMode : int { MODE_FILE = 0, MODE_BYPASS = 1, MODE_DSP = 2, MODE_SILENCE = 3 };
constexpr uint32_t kQueueSlots = 16;
constexpr uint32_t kMaxSamples = 4096;

struct AudioFrame {
    uint64_t sequence = 0;
    int64_t capture_ns = 0;
    int sample_rate = 48000;
    int channels = 1;
    int frames = 0;
    std::array<float, kMaxSamples> samples{};
};

class FrameRing {
public:
    bool write(const AudioFrame &frame, bool *dropped_oldest = nullptr) {
        if (dropped_oldest != nullptr) *dropped_oldest = false;
        uint32_t head = head_.load(std::memory_order_relaxed);
        uint32_t next = (head + 1u) % kQueueSlots;
        uint32_t tail = tail_.load(std::memory_order_acquire);
        if (next == tail) {
            // Keep latency bounded. The producer owns head; advancing tail with a
            // CAS safely discards the oldest frame if the consumer did not take it.
            uint32_t advanced = (tail + 1u) % kQueueSlots;
            if (tail_.compare_exchange_strong(tail, advanced,
                                              std::memory_order_acq_rel,
                                              std::memory_order_acquire)) {
                if (dropped_oldest != nullptr) *dropped_oldest = true;
            } else {
                // The consumer advanced concurrently, so the slot is available.
            }
        }
        slots_[head] = frame;
        head_.store(next, std::memory_order_release);
        return true;
    }
    bool read(AudioFrame &frame) {
        uint32_t tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) return false;
        frame = slots_[tail];
        tail_.store((tail + 1u) % kQueueSlots, std::memory_order_release);
        return true;
    }
    void clear() {
        tail_.store(head_.load(std::memory_order_acquire), std::memory_order_release);
    }
private:
    std::array<AudioFrame, kQueueSlots> slots_{};
    std::atomic<uint32_t> head_{0};
    std::atomic<uint32_t> tail_{0};
};

pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
std::vector<int16_t> g_samples;
int g_channels = 1;
int g_rate = 44100;
std::atomic<size_t> g_frame{0};
std::string g_path;
time_t g_mtime = 0;
off_t g_size = 0;
std::atomic<int> g_on{0};
std::atomic<int> g_mode{MODE_FILE};
std::atomic<int> g_profile{0};
std::atomic<float> g_input_gain{1.0f};
std::atomic<float> g_pitch{4.0f};
std::atomic<float> g_formant{0.25f};
std::atomic<int> g_underflow_silence{0};
std::atomic<int> g_java_pcm{0};
std::atomic<uint32_t> g_tick{0};
int g_installed = 0;
std::atomic<uint64_t> g_sequence{0};
std::atomic<uint64_t> g_input_drops{0};
std::atomic<uint64_t> g_output_drops{0};
std::atomic<uint64_t> g_underflows{0};
std::atomic<uint64_t> g_audio_record_reads{0};
std::atomic<uint64_t> g_aaudio_reads{0};
std::atomic<uint64_t> g_aaudio_callbacks{0};
std::atomic<uint64_t> g_java_reads{0};
std::atomic<uint64_t> g_processed_frames{0};
std::atomic<uint64_t> g_processed_blocks{0};
std::atomic<uint64_t> g_processing_ns{0};
std::atomic<uint64_t> g_processing_max_ns{0};
std::atomic<int> g_worker_running{0};
std::atomic<int> g_diagnostics_running{0};
pthread_t g_worker{};
pthread_t g_diagnostics{};
FrameRing g_input_ring;
FrameRing g_output_ring;
thread_local int64_t g_last_native_dsp_ns = 0;
// ApplyDsp runs only on VoiceWorker, so these values can carry filter and
// oscillator state across AudioRecord blocks without synchronization.
std::array<float, 8> g_previous_input{};
double g_robot_phase = 0.0;
constexpr int kPitchRingSamples = 4096;
std::array<std::array<float, kPitchRingSamples>, 8> g_pitch_delay{};
int g_pitch_write = 0;
double g_pitch_phase = 0.0;

using SetDataCallback = int32_t (*)(void *builder, void *callback, void *user_data);
using DataCallback = int32_t (*)(void *stream, void *user_data, void *audio, int32_t frames);
using StreamInt = int32_t (*)(void *stream);
using AudioRead = ssize_t (*)(void *, void *, size_t, bool);
using AAudioRead = int32_t (*)(void *, void *, int32_t, int64_t);

SetDataCallback g_orig_set_callback = nullptr;
StreamInt g_format = nullptr;
StreamInt g_channel_count = nullptr;
StreamInt g_sample_rate = nullptr;
AudioRead g_orig_audio_read = nullptr;
AudioRead g_orig_audio_read_u32 = nullptr;
AAudioRead g_orig_aaudio_read = nullptr;
std::array<CbBox, 32> g_callback_boxes{};
std::atomic<uint32_t> g_callback_boxes_used{0};

int64_t MonotonicNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

float Clamp(float value) {
    return value < -1.0f ? -1.0f : (value > 1.0f ? 1.0f : value);
}

float ReadPitchDelay(int channel, double delay) {
    double position = static_cast<double>(g_pitch_write) - delay;
    while (position < 0.0) position += kPitchRingSamples;
    while (position >= kPitchRingSamples) position -= kPitchRingSamples;
    int a = static_cast<int>(position);
    int b = (a + 1) % kPitchRingSamples;
    float mix = static_cast<float>(position - a);
    return g_pitch_delay[channel][a] * (1.0f - mix)
            + g_pitch_delay[channel][b] * mix;
}

void ApplyDsp(AudioFrame &frame) {
    const int profile = g_profile.load(std::memory_order_relaxed);
    float pitch = g_pitch.load(std::memory_order_relaxed);
    float formant = g_formant.load(std::memory_order_relaxed);
    float gain = g_input_gain.load(std::memory_order_relaxed);
    if (profile == 1) { pitch = 8.0f; formant = 0.45f; }
    else if (profile == 2) { pitch = -6.0f; formant = -0.35f; }
    else if (profile == 3) { pitch = 0.0f; formant = 0.0f; }
    const float ratio = std::pow(2.0f, pitch / 12.0f);
    const int count = frame.frames * frame.channels;
    std::array<float, kMaxSamples> source{};
    std::copy_n(frame.samples.begin(), count, source.begin());
    double robot_phase = g_robot_phase;
    const double robot_step = 2.0 * 3.14159265358979323846 * 70.0
            / static_cast<double>(frame.sample_rate);
    const int pitch_window = std::max(64, std::min(kPitchRingSamples - 2,
                                                   frame.sample_rate / 50));
    const double pitch_step = std::fabs(static_cast<double>(ratio) - 1.0)
            / static_cast<double>(pitch_window);
    for (int i = 0; i < frame.frames; ++i) {
        for (int channel = 0; channel < frame.channels; ++channel) {
            g_pitch_delay[channel][g_pitch_write] = source[i * frame.channels + channel];
        }
        double second_phase = g_pitch_phase + 0.5;
        if (second_phase >= 1.0) second_phase -= 1.0;
        double delay_a = (ratio >= 1.0f ? 1.0 - g_pitch_phase : g_pitch_phase) * pitch_window;
        double delay_b = (ratio >= 1.0f ? 1.0 - second_phase : second_phase) * pitch_window;
        float weight_a = static_cast<float>(0.5 - 0.5 * std::cos(
                2.0 * 3.14159265358979323846 * g_pitch_phase));
        float weight_b = 1.0f - weight_a;
        for (int channel = 0; channel < frame.channels; ++channel) {
            float x = source[i * frame.channels + channel];
            if (profile != 3 && std::fabs(ratio - 1.0f) > 0.001f) {
                x = ReadPitchDelay(channel, delay_a) * weight_a
                        + ReadPitchDelay(channel, delay_b) * weight_b;
            }
            float previous = g_previous_input[channel];
            g_previous_input[channel] = x;
            float shaped = x + formant * (x - previous);
            if (profile == 3) {
                shaped *= static_cast<float>(std::sin(robot_phase));
            }
            shaped *= gain;
            // Soft noise gate: silence low-level room noise without a hard edge.
            float magnitude = std::fabs(shaped);
            constexpr float gate = 0.003f;
            if (magnitude < gate) shaped *= magnitude / gate;
            // Smooth limiter avoids the harsh edge produced by hard clipping.
            frame.samples[i * frame.channels + channel] = std::tanh(shaped);
        }
        if (profile == 3) {
            robot_phase += robot_step;
            if (robot_phase >= 2.0 * 3.14159265358979323846) {
                robot_phase -= 2.0 * 3.14159265358979323846;
            }
        } else {
            g_pitch_phase += pitch_step;
            if (g_pitch_phase >= 1.0) g_pitch_phase -= 1.0;
        }
        g_pitch_write = (g_pitch_write + 1) % kPitchRingSamples;
    }
    g_robot_phase = robot_phase;
}

void *VoiceWorker(void *) {
    AudioFrame frame;
    while (g_worker_running.load(std::memory_order_acquire)) {
        if (!g_input_ring.read(frame)) {
            usleep(1000);
            continue;
        }
        int64_t started = MonotonicNs();
        ApplyDsp(frame);
        uint64_t elapsed = static_cast<uint64_t>(MonotonicNs() - started);
        g_processed_frames.fetch_add(static_cast<uint64_t>(frame.frames), std::memory_order_relaxed);
        g_processed_blocks.fetch_add(1, std::memory_order_relaxed);
        g_processing_ns.fetch_add(elapsed, std::memory_order_relaxed);
        uint64_t previous_max = g_processing_max_ns.load(std::memory_order_relaxed);
        while (elapsed > previous_max && !g_processing_max_ns.compare_exchange_weak(
                previous_max, elapsed, std::memory_order_relaxed)) {}
        bool dropped = false;
        g_output_ring.write(frame, &dropped);
        if (dropped) g_output_drops.fetch_add(1, std::memory_order_relaxed);
    }
    return nullptr;
}

void *DiagnosticsWorker(void *) {
    uint64_t last_ar = 0;
    uint64_t last_aaudio = 0;
    uint64_t last_callbacks = 0;
    uint64_t last_java = 0;
    while (g_diagnostics_running.load(std::memory_order_acquire)) {
        sleep(5);
        uint64_t ar = g_audio_record_reads.load(std::memory_order_relaxed);
        uint64_t aa = g_aaudio_reads.load(std::memory_order_relaxed);
        uint64_t cb = g_aaudio_callbacks.load(std::memory_order_relaxed);
        uint64_t java_reads = g_java_reads.load(std::memory_order_relaxed);
        if (ar == last_ar && aa == last_aaudio && cb == last_callbacks && java_reads == last_java) continue;
        uint64_t processed = g_processed_frames.load(std::memory_order_relaxed);
        uint64_t blocks = g_processed_blocks.load(std::memory_order_relaxed);
        uint64_t total_ns = g_processing_ns.load(std::memory_order_relaxed);
        uint64_t max_ns = g_processing_max_ns.load(std::memory_order_relaxed);
        SPOOF_AUDIO_LOG("capture stats mode=%d JavaRead=%llu AudioRecord=%llu AAudioRead=%llu callback=%llu "
                        "processed=%llu avg_us=%llu max_us=%llu input_drop=%llu output_drop=%llu underflow=%llu",
                        g_mode.load(std::memory_order_relaxed),
                        (unsigned long long) java_reads,
                        (unsigned long long) ar, (unsigned long long) aa,
                        (unsigned long long) cb, (unsigned long long) processed,
                        (unsigned long long) (blocks == 0 ? 0 : total_ns / blocks / 1000),
                        (unsigned long long) (max_ns / 1000),
                        (unsigned long long) g_input_drops.load(std::memory_order_relaxed),
                        (unsigned long long) g_output_drops.load(std::memory_order_relaxed),
                        (unsigned long long) g_underflows.load(std::memory_order_relaxed));
        last_ar = ar;
        last_aaudio = aa;
        last_callbacks = cb;
        last_java = java_reads;
    }
    return nullptr;
}

void StartWorker() {
    if (g_mode.load(std::memory_order_acquire) != MODE_DSP) return;
    int expected = 0;
    if (!g_worker_running.compare_exchange_strong(expected, 1)) return;
    if (pthread_create(&g_worker, nullptr, VoiceWorker, nullptr) != 0) {
        g_worker_running.store(0, std::memory_order_release);
        g_mode.store(MODE_BYPASS, std::memory_order_release);
        SPOOF_AUDIO_LOG("E_ENGINE_INIT_FAILED pthread_create");
    }
}

void StartDiagnostics() {
    int expected = 0;
    if (!g_diagnostics_running.compare_exchange_strong(expected, 1)) return;
    if (pthread_create(&g_diagnostics, nullptr, DiagnosticsWorker, nullptr) != 0) {
        g_diagnostics_running.store(0, std::memory_order_release);
        SPOOF_AUDIO_LOG("diagnostics thread unavailable");
    }
}

void ProcessLive(void *buffer, int frames, int channels, bool as_float, int sample_rate) {
    if (buffer == nullptr || frames <= 0 || channels <= 0 || channels > 8
            || static_cast<uint64_t>(frames) * channels > kMaxSamples) return;
    AudioFrame input;
    input.sequence = g_sequence.fetch_add(1, std::memory_order_relaxed) + 1;
    input.capture_ns = MonotonicNs();
    input.sample_rate = sample_rate > 0 ? sample_rate : 48000;
    input.channels = channels;
    input.frames = frames;
    int count = frames * channels;
    if (as_float) {
        std::copy_n(static_cast<float *>(buffer), count, input.samples.begin());
    } else {
        auto *samples = static_cast<int16_t *>(buffer);
        for (int i = 0; i < count; ++i) input.samples[i] = samples[i] / 32768.0f;
    }
    bool input_dropped = false;
    g_input_ring.write(input, &input_dropped);
    if (input_dropped) g_input_drops.fetch_add(1, std::memory_order_relaxed);
    AudioFrame output;
    if (!g_output_ring.read(output) || output.frames != frames || output.channels != channels) {
        g_underflows.fetch_add(1, std::memory_order_relaxed);
        if (g_underflow_silence.load(std::memory_order_relaxed)) {
            memset(buffer, 0, static_cast<size_t>(count) * (as_float ? sizeof(float) : sizeof(int16_t)));
        }
        return;
    }
    if (as_float) {
        std::copy_n(output.samples.begin(), count, static_cast<float *>(buffer));
    } else {
        auto *samples = static_cast<int16_t *>(buffer);
        for (int i = 0; i < count; ++i) samples[i] = static_cast<int16_t>(Clamp(output.samples[i]) * 32767.0f);
    }
}

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
    g_frame.store(0, std::memory_order_release);
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
    auto *out = static_cast<uint8_t *>(buffer);
    if (g_samples.empty()) {
        memset(out, 0, size);
    } else {
        const auto *bytes = reinterpret_cast<const uint8_t *>(g_samples.data());
        size_t total = g_samples.size() * 2;
        size_t pos = (g_frame.fetch_add((size + 1) / 2, std::memory_order_relaxed) * 2) % total;
        for (size_t i = 0; i < size; i++) {
            out[i] = bytes[pos];
            pos++;
            if (pos >= total) pos = 0;
        }
    }
}

ssize_t HandleAudioRead(AudioRead original, void *self, void *buffer, size_t size, bool blocking) {
    // Voice reads are a few kilobytes. A wild size means this symbol is not
    // the byte-count read, so leave the caller buffer alone.
    if (buffer == nullptr || size == 0) return 0;
    int mode = g_mode.load(std::memory_order_acquire);
    if (size > 256u * 1024u) {
        return original == nullptr ? -1 : original(self, buffer, size, blocking);
    }
    g_audio_record_reads.fetch_add(1, std::memory_order_relaxed);
    if (mode == MODE_FILE) {
        CopyFakeBytes(buffer, size);
        return static_cast<ssize_t>(size);
    }
    if (mode == MODE_SILENCE) {
        memset(buffer, 0, size);
        return static_cast<ssize_t>(size);
    }
    if (original == nullptr) return -1;
    ssize_t result = original(self, buffer, size, blocking);
    if (mode == MODE_DSP && result > 1) {
        ProcessLive(buffer, static_cast<int>(result / sizeof(int16_t)), 1, false, 48000);
        g_last_native_dsp_ns = MonotonicNs();
    }
    return result;
}

ssize_t SpoofAudioRead(void *self, void *buffer, size_t size, bool blocking) {
    return HandleAudioRead(g_orig_audio_read, self, buffer, size, blocking);
}

ssize_t SpoofAudioReadU32(void *self, void *buffer, uint32_t size, bool blocking) {
    return HandleAudioRead(g_orig_audio_read_u32, self, buffer, size, blocking);
}

ssize_t SpoofAudioReadTimeout(void *self, void *buffer, size_t size, bool blocking, int64_t timeout) {
    (void) timeout;
    return SpoofAudioRead(self, buffer, size, blocking);
}

void FillFrames(void *buffer, int32_t frames, int channels, bool as_float) {
    if (channels < 1) channels = 1;
    if (channels > 8) channels = 8;
    int src_channels = g_channels > 0 ? g_channels : 1;
    size_t src_frames = g_samples.empty() ? 0 : g_samples.size() / (size_t) src_channels;
    size_t first_frame = g_frame.fetch_add(static_cast<size_t>(frames), std::memory_order_relaxed);
    auto *out = static_cast<uint8_t *>(buffer);
    for (int32_t i = 0; i < frames; i++) {
        int16_t frame[8];
        memset(frame, 0, sizeof(frame));
        if (src_frames > 0) {
            size_t index = ((first_frame + static_cast<size_t>(i)) % src_frames) * (size_t) src_channels;
            for (int c = 0; c < src_channels && c < 8; c++) frame[c] = g_samples[index + c];
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
}

int32_t SpoofAAudioRead(void *stream, void *buffer, int32_t frames, int64_t timeout) {
    if (buffer == nullptr || frames <= 0) return 0;
    int format = g_format != nullptr ? g_format(stream) : 1;
    int channels = g_channel_count != nullptr ? g_channel_count(stream) : 1;
    int mode = g_mode.load(std::memory_order_acquire);
    g_aaudio_reads.fetch_add(1, std::memory_order_relaxed);
    if (mode == MODE_FILE) {
        FillFrames(buffer, frames, channels, format == 2);
        return frames;
    }
    if (mode == MODE_SILENCE) {
        memset(buffer, 0, static_cast<size_t>(frames) * channels
                * (format == 2 ? sizeof(float) : sizeof(int16_t)));
        return frames;
    }
    if (g_orig_aaudio_read == nullptr) return -2;
    int32_t result = g_orig_aaudio_read(stream, buffer, frames, timeout);
    if (mode == MODE_DSP && result > 0) {
        int rate = g_sample_rate != nullptr ? g_sample_rate(stream) : 48000;
        ProcessLive(buffer, result, channels, format == 2, rate);
        g_last_native_dsp_ns = MonotonicNs();
    }
    return result;
}

int32_t SpoofDataCallback(void *stream, void *user_data, void *audio, int32_t frames) {
    auto *box = static_cast<CbBox *>(user_data);
    int format = g_format != nullptr ? g_format(stream) : 1;
    int channels = g_channel_count != nullptr ? g_channel_count(stream) : 1;
    int mode = g_mode.load(std::memory_order_acquire);
    g_aaudio_callbacks.fetch_add(1, std::memory_order_relaxed);
    if (audio != nullptr && frames > 0) {
        if (mode == MODE_FILE) FillFrames(audio, frames, channels, format == 2);
        else if (mode == MODE_SILENCE) {
            memset(audio, 0, static_cast<size_t>(frames) * channels
                    * (format == 2 ? sizeof(float) : sizeof(int16_t)));
        } else if (mode == MODE_DSP) {
            int rate = g_sample_rate != nullptr ? g_sample_rate(stream) : 48000;
            ProcessLive(audio, frames, channels, format == 2, rate);
        }
    }
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
    uint32_t index = g_callback_boxes_used.fetch_add(1, std::memory_order_relaxed);
    if (index >= g_callback_boxes.size()) return g_orig_set_callback(builder, callback, user_data);
    auto *box = &g_callback_boxes[index];
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

int HookSymbol(void *handle, const char *name, void *replacement, void **saved_origin = nullptr) {
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
    if (saved_origin != nullptr) *saved_origin = origin;
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
    g_frame.store(0, std::memory_order_release);
    pthread_mutex_unlock(&g_mu);
    g_java_pcm.store(1, std::memory_order_release);
    SPOOF_AUDIO_LOG("pcm from java %u samples %d Hz x%d", (unsigned) (bytes / 2), g_rate, g_channels);
}

jboolean enableMicTap(JNIEnv *env, jclass, jstring path, jbyteArray pcm, jint rate, jint channels,
                      jint mode, jint profile, jfloat input_gain, jfloat pitch_semitones,
                      jfloat formant_shift, jboolean underflow_silence) {
    if (g_installed) return g_installed > 0 ? JNI_TRUE : JNI_FALSE;
    const char *chars = nullptr;
    if (path != nullptr) chars = env->GetStringUTFChars(path, nullptr);
    if (chars != nullptr && chars[0] != '\0') g_path = chars;
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    UsePcm(env, pcm, rate, channels);
    if (g_samples.empty() && !g_path.empty()) LoadWav(g_path.c_str());
    if (g_samples.empty()) SPOOF_AUDIO_LOG("no pcm, microphone reads stay silent");

    g_mode.store(mode >= MODE_FILE && mode <= MODE_SILENCE ? mode : MODE_FILE,
                 std::memory_order_release);
    g_profile.store(profile >= 0 && profile <= 3 ? profile : 0, std::memory_order_release);
    g_input_gain.store(std::max(0.0f, std::min(4.0f, input_gain)), std::memory_order_release);
    g_pitch.store(std::max(-12.0f, std::min(12.0f, pitch_semitones)), std::memory_order_release);
    g_formant.store(std::max(-1.0f, std::min(1.0f, formant_shift)), std::memory_order_release);
    g_underflow_silence.store(underflow_silence == JNI_TRUE ? 1 : 0, std::memory_order_release);

    g_on.store(1, std::memory_order_release);
    int hooked = 0;
    void *audio = xdl_open("libaudioclient.so", XDL_TRY_FORCE_LOAD);
    hooked += HookSymbol(audio, "_ZN7android11AudioRecord4readEPvmb",
                         reinterpret_cast<void *>(SpoofAudioRead),
                         reinterpret_cast<void **>(&g_orig_audio_read));
    hooked += HookSymbol(audio, "_ZN7android11AudioRecord4readEPvjb",
                         reinterpret_cast<void *>(SpoofAudioReadU32),
                         reinterpret_cast<void **>(&g_orig_audio_read_u32));
    void *aaudio = xdl_open("libaaudio.so", XDL_TRY_FORCE_LOAD);
    g_format = reinterpret_cast<StreamInt>(FindSymbol(aaudio, "AAudioStream_getFormat"));
    g_channel_count = reinterpret_cast<StreamInt>(FindSymbol(aaudio, "AAudioStream_getChannelCount"));
    g_sample_rate = reinterpret_cast<StreamInt>(FindSymbol(aaudio, "AAudioStream_getSampleRate"));
    hooked += HookSymbol(aaudio, "AAudioStream_read", reinterpret_cast<void *>(SpoofAAudioRead),
                         reinterpret_cast<void **>(&g_orig_aaudio_read));
    void *set_callback = FindSymbol(aaudio, "AAudioStreamBuilder_setDataCallback");
    if (set_callback != nullptr && DobbyHook(set_callback, reinterpret_cast<void *>(SpoofSetDataCallback),
                                             reinterpret_cast<void **>(&g_orig_set_callback)) == 0) {
        SPOOF_AUDIO_LOG("hooked AAudioStreamBuilder_setDataCallback");
        hooked++;
    } else {
        SPOOF_AUDIO_LOG("miss AAudioStreamBuilder_setDataCallback");
    }
    SPOOF_AUDIO_LOG("native mic symbols %d", hooked);
    StartWorker();
    StartDiagnostics();
    SPOOF_AUDIO_LOG("voice engine mode=%d profile=%d gain=%.2f pitch=%.2f formant=%.2f",
                    g_mode.load(), g_profile.load(), g_input_gain.load(), g_pitch.load(), g_formant.load());
    g_installed = hooked > 0 ? 1 : -1;
    return hooked > 0 ? JNI_TRUE : JNI_FALSE;
}

void processMicBytes(JNIEnv *env, jclass, jbyteArray pcm, jint offset, jint byte_count,
                     jint rate, jint channels) {
    if (pcm == nullptr || offset < 0 || byte_count <= 1 || channels <= 0) return;
    jsize length = env->GetArrayLength(pcm);
    if (offset > length || byte_count > length - offset) return;
    if (MonotonicNs() - g_last_native_dsp_ns < 5000000LL) return;
    auto *data = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(pcm, nullptr));
    if (data == nullptr) return;
    g_java_reads.fetch_add(1, std::memory_order_relaxed);
    ProcessLive(data + offset, byte_count / (2 * channels), channels, false, rate);
    env->ReleasePrimitiveArrayCritical(pcm, data, 0);
}

void processMicShorts(JNIEnv *env, jclass, jshortArray pcm, jint offset, jint sample_count,
                      jint rate, jint channels) {
    if (pcm == nullptr || offset < 0 || sample_count <= 0 || channels <= 0) return;
    jsize length = env->GetArrayLength(pcm);
    if (offset > length || sample_count > length - offset) return;
    if (MonotonicNs() - g_last_native_dsp_ns < 5000000LL) return;
    auto *data = static_cast<jshort *>(env->GetPrimitiveArrayCritical(pcm, nullptr));
    if (data == nullptr) return;
    g_java_reads.fetch_add(1, std::memory_order_relaxed);
    ProcessLive(data + offset, sample_count / channels, channels, false, rate);
    env->ReleasePrimitiveArrayCritical(pcm, data, 0);
}

void processMicFloats(JNIEnv *env, jclass, jfloatArray pcm, jint offset, jint sample_count,
                      jint rate, jint channels) {
    if (pcm == nullptr || offset < 0 || sample_count <= 0 || channels <= 0) return;
    jsize length = env->GetArrayLength(pcm);
    if (offset > length || sample_count > length - offset) return;
    if (MonotonicNs() - g_last_native_dsp_ns < 5000000LL) return;
    auto *data = static_cast<jfloat *>(env->GetPrimitiveArrayCritical(pcm, nullptr));
    if (data == nullptr) return;
    g_java_reads.fetch_add(1, std::memory_order_relaxed);
    ProcessLive(data + offset, sample_count / channels, channels, true, rate);
    env->ReleasePrimitiveArrayCritical(pcm, data, 0);
}

void processMicDirect(JNIEnv *env, jclass, jobject pcm, jint byte_count, jint rate,
                      jint channels, jboolean float_pcm) {
    if (pcm == nullptr || byte_count <= 0 || channels <= 0) return;
    if (MonotonicNs() - g_last_native_dsp_ns < 5000000LL) return;
    auto *data = static_cast<uint8_t *>(env->GetDirectBufferAddress(pcm));
    jlong capacity = env->GetDirectBufferCapacity(pcm);
    if (data == nullptr || capacity < byte_count) return;
    int sample_bytes = float_pcm == JNI_TRUE ? 4 : 2;
    g_java_reads.fetch_add(1, std::memory_order_relaxed);
    ProcessLive(data, byte_count / (sample_bytes * channels), channels,
                float_pcm == JNI_TRUE, rate);
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
