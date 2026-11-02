package io.github.bluecartooncat.mirra;

/** Transfers scrcpy H.264 packets to the active display decoder. */
final class ScrcpyFrameRelay {
    interface Sink {
        void onFrame(byte[] bytes, long pts, boolean config, boolean key);
        void onSourceStopped();
    }

    private static Sink sink;
    private static byte[] configuration;
    private static boolean active;
    private static Object owner;

    static synchronized void start(Object source) {
        if (owner != null && owner != source) throw new IllegalStateException("External display already active");
        owner = source; active = true; configuration = null;
    }
    static synchronized boolean attach(Sink consumer) {
        if (!active || sink != null) return false;
        sink = consumer;
        if (configuration != null) consumer.onFrame(configuration, 0, true, false);
        return true;
    }
    static synchronized void detach(Sink consumer) { if (sink == consumer) sink = null; }
    static synchronized void publish(Object source, byte[] data, long pts, boolean config, boolean key) {
        if (!active || owner != source) return;
        if (config) configuration = data;
        if (sink != null) sink.onFrame(data, pts, config, key);
    }
    static synchronized void stop(Object source) {
        if (owner != source) return;
        owner = null;
        active = false;
        configuration = null;
        Sink previous = sink;
        sink = null;
        if (previous != null) previous.onSourceStopped();
    }
}
