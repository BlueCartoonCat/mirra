package io.github.bluecartooncat.mirra;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Decodes scrcpy's external-display H.264 into the Miracast renderer. */
final class ScrcpyVideoDecoder implements ScrcpyFrameRelay.Sink {
    private static final class Frame {
        final byte[] data;
        final long pts;
        final int flags;
        Frame(byte[] data, long pts, int flags) { this.data = data; this.pts = pts; this.flags = flags; }
    }

    private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(60);
    private final MediaCodec decoder;
    private volatile boolean running;
    private boolean waitingForKey = true;
    private byte[] configuration;
    private Thread worker;

    ScrcpyVideoDecoder(Surface surface) throws Exception {
        ScrcpySession session = ScrcpySession.active();
        if (session == null) throw new IllegalStateException("External display source is not ready");
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            decoder.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                    session.width, session.height), surface, null, 0);
            decoder.start();
        } catch (Exception e) { decoder.release(); throw e; }
    }

    void start() {
        running = true;
        if (!ScrcpyFrameRelay.attach(this)) {
            running = false;
            decoder.stop(); decoder.release();
            throw new IllegalStateException("External display source is not ready");
        }
        worker = new Thread(this::decode, "ScrcpyDisplayDecoder");
        worker.start();
        ScrcpySession session = ScrcpySession.active();
        if (session != null) session.requestVideoReset();
    }

    @Override public synchronized void onFrame(byte[] bytes, long pts, boolean config, boolean key) {
        if (!running) return;
        if (config) { configuration = bytes; queue.clear(); waitingForKey = true; return; }
        if (waitingForKey) {
            if (!key || configuration == null) return;
            queue.clear();
            queue.offer(new Frame(configuration, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG));
            waitingForKey = false;
            Log.i("ScrcpyDecoder", "Received video configuration and keyframe");
        }
        if (!queue.offer(new Frame(bytes, pts, 0))) {
            queue.clear();
            waitingForKey = true;
            Log.w("ScrcpyDecoder", "Fell behind; waiting for the next key frame");
            ScrcpySession session = ScrcpySession.active();
            if (session != null) session.requestVideoReset();
        }
    }

    @Override public void onSourceStopped() { running = false; }

    private void decode() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Frame frame = null;
        boolean loggedOutput = false;
        try {
            while (running) {
                if (frame == null) frame = queue.poll(10, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    int index = decoder.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        if (buffer == null || buffer.capacity() < frame.data.length)
                            throw new IllegalStateException("Scrcpy packet exceeds decoder buffer");
                        buffer.clear(); buffer.put(frame.data);
                        decoder.queueInputBuffer(index, 0, frame.data.length, frame.pts, frame.flags);
                        frame = null;
                    }
                }
                int index;
                while ((index = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                    decoder.releaseOutputBuffer(index, true);
                    if (!loggedOutput) {
                        Log.i("ScrcpyDecoder", "Rendered first decoded frame");
                        loggedOutput = true;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running) Log.e("ScrcpyDecoder", "External display decoder failed", e);
        } finally {
            running = false;
            ScrcpyFrameRelay.detach(this);
            try { decoder.stop(); } catch (Exception ignored) { }
            decoder.release();
        }
    }

    void stop() {
        running = false;
        ScrcpyFrameRelay.detach(this);
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            try { worker.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
