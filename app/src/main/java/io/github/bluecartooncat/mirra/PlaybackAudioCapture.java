package io.github.bluecartooncat.mirra;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures permitted playback into bounded, 10 ms stereo LPCM blocks. */
final class PlaybackAudioCapture {
    private static final String TAG = "PlaybackAudioCapture";
    static final int BLOCK_BYTES = 480 * 2 * 2; // 48 kHz, stereo, 16-bit, 10 ms
    private static final int SAMPLE_RATE = 48000;

    private final MediaProjection projection;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ArrayBlockingQueue<byte[]> blocks = new ArrayBlockingQueue<>(25);
    private AudioRecord recorder;
    private Thread thread;

    PlaybackAudioCapture(MediaProjection projection) {
        this.projection = projection;
    }

    boolean start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Playback audio capture requires Android 10 or newer");
            return false;
        }
        try {
            AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Stereo 48 kHz capture unavailable");
            recorder = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minimum, BLOCK_BYTES * 4))
                    .setAudioPlaybackCaptureConfig(capture)
                    .build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("Audio recorder could not initialize");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Playback recording did not start");
            running.set(true);
            thread = new Thread(this::capture, "PlaybackAudioCapture");
            thread.start();
            Log.i(TAG, "Capturing permitted device playback at 48 kHz stereo");
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Playback capture unavailable; sending silence", e);
            releaseRecorder();
            return false;
        }
    }

    private void capture() {
        try {
            while (running.get()) {
                byte[] block = new byte[BLOCK_BYTES];
                int offset = 0;
                while (running.get() && offset < block.length) {
                    int count = recorder.read(block, offset, block.length - offset);
                    if (count < 0) throw new IllegalStateException("AudioRecord read failed: " + count);
                    if (count == 0) continue;
                    offset += count;
                }
                if (!running.get()) break;
                // AudioRecord returns native little-endian samples; WFD LPCM is big-endian.
                for (int i = 0; i < block.length; i += 2) {
                    byte low = block[i]; block[i] = block[i + 1]; block[i + 1] = low;
                }
                if (!blocks.offer(block)) {
                    blocks.poll(); // Prefer current audio over unbounded latency.
                    blocks.offer(block);
                }
            }
        } catch (RuntimeException e) {
            if (running.get()) Log.e(TAG, "Playback audio capture stopped; sending silence", e);
        } finally {
            running.set(false);
        }
    }

    byte[] nextBlock() {
        byte[] block = blocks.poll();
        return block == null ? new byte[BLOCK_BYTES] : block;
    }

    void clearBufferedAudio() { blocks.clear(); }

    void stop() {
        running.set(false);
        if (recorder != null) {
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
        }
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        releaseRecorder();
        blocks.clear();
    }

    private void releaseRecorder() {
        if (recorder != null) { recorder.release(); recorder = null; }
    }
}
