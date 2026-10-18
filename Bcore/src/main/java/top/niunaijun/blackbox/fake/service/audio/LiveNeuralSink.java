package top.niunaijun.blackbox.fake.service.audio;

import android.content.Context;
import java.nio.ByteBuffer;

/**
 * App-side live neural entry. The audio read thread never calls {@link #process}.
 * {@code VoiceWorker} does, through {@code VoiceFilterRouter.dispatchNeural}.
 */
public interface LiveNeuralSink {
    void ensureActivated(Context context, String backend, int sampleRate);

    void noteSampleRate(int sampleRate);

    boolean isReady();

    /**
     * @return true when [out] holds converted PCM. False means the caller writes silence.
     */
    boolean process(ByteBuffer in, ByteBuffer out, int frames, int channels, int sampleRate);
}
