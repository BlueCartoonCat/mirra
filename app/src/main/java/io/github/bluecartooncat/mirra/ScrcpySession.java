package io.github.bluecartooncat.mirra;

import android.content.Context;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns one shell scrcpy server and its video/control bridge processes. */
final class ScrcpySession {
    static final String SERVER_VERSION = "5.0.1";
    private static final String TAG = "ScrcpySession";
    final int width, height;
    private final int fps, bitrateMbps, dpi;
    private final boolean audioEnabled;
    private final String defaultApp;
    private final boolean touchMouse;
    private static volatile ScrcpySession active;

    private final Context context;
    private final String id = String.format(java.util.Locale.US, "%08x", new SecureRandom().nextInt() & 0x7fffffff);
    private final String remoteJar = "/data/local/tmp/mirra-scrcpy-" + id + ".jar";
    private final ExecutorService controls = Executors.newSingleThreadExecutor();
    private final Object controlQueueLock = new Object();
    private static final class MouseMovement { int x, y; }
    private MouseMovement queuedMouseMovement;
    private float mouseRemainderX, mouseRemainderY;
    private volatile boolean stopped;
    private Process server, videoBridge, audioBridge, controlBridge;
    private final java.util.concurrent.ArrayBlockingQueue<byte[]> audioBlocks = new java.util.concurrent.ArrayBlockingQueue<>(25);
    private Thread videoReader;
    private volatile float cursorX = .5f, cursorY = .5f;
    private volatile boolean cursorVisible;
    private boolean mousePressed;
    private static final int MOUSE_ID = 1;
    private static final int REMOTE_ID = 2;
    private static final int GAMEPAD_ID = 3;
    private static final int REMOTE_CONSUMER_ID = 4;
    private int remoteConsumerKeys;
    private static final byte[] REMOTE_CONSUMER_DESCRIPTOR = new byte[]{
            0x05,0x0c,0x09,0x01,(byte)0xa1,0x01,
            0x0a,0x24,0x02,0x0a,0x23,0x02,0x15,0x00,0x25,0x01,
            0x75,0x01,(byte)0x95,0x02,(byte)0x81,0x02,
            0x75,0x06,(byte)0x95,0x01,(byte)0x81,0x01,(byte)0xc0};
    // Generic HID gamepad: 16 buttons, six 8-bit axes, and an eight-way hat.
    private static final byte[] GAMEPAD_DESCRIPTOR = new byte[]{
            0x05,0x01,0x09,0x05,(byte)0xa1,0x01,
            0x05,0x09,0x19,0x01,0x29,0x10,0x15,0x00,0x25,0x01,
            0x75,0x01,(byte)0x95,0x10,(byte)0x81,0x02,
            0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x33,0x09,0x34,0x09,0x32,0x09,0x35,
            0x15,0x00,0x26,(byte)0xff,0x00,0x75,0x08,(byte)0x95,0x06,(byte)0x81,0x02,
            0x09,0x39,0x15,0x00,0x25,0x07,0x35,0x00,0x46,0x3b,0x01,0x65,0x14,
            0x75,0x04,(byte)0x95,0x01,(byte)0x81,0x42,
            0x75,0x04,(byte)0x95,0x01,(byte)0x81,0x01,(byte)0xc0};
    private final java.util.Set<Integer> remoteKeys = new java.util.LinkedHashSet<>();
    private static final byte[] KEYBOARD_DESCRIPTOR = new byte[]{
            0x05,0x01,0x09,0x06,(byte)0xa1,0x01,0x05,0x07,0x19,(byte)0xe0,0x29,(byte)0xe7,
            0x15,0x00,0x25,0x01,0x75,0x01,(byte)0x95,0x08,(byte)0x81,0x02,
            (byte)0x95,0x01,0x75,0x08,(byte)0x81,0x01,(byte)0x95,0x06,0x75,0x08,
            0x15,0x00,0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,(byte)0x81,0x00,(byte)0xc0};
    // Standard relative mouse HID report descriptor (buttons, X, Y, wheel, pan).
    private static final byte[] MOUSE_DESCRIPTOR = new byte[]{
            0x05,0x01,0x09,0x02,(byte)0xa1,0x01,0x09,0x01,(byte)0xa1,0x00,
            0x05,0x09,0x19,0x01,0x29,0x05,0x15,0x00,0x25,0x01,(byte)0x95,0x05,
            0x75,0x01,(byte)0x81,0x02,(byte)0x95,0x01,0x75,0x03,(byte)0x81,0x01,
            0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,(byte)0x81,
            0x25,0x7f,0x75,0x08,(byte)0x95,0x03,(byte)0x81,0x06,0x05,0x0c,
            0x0a,0x38,0x02,0x15,(byte)0x81,0x25,0x7f,0x75,0x08,(byte)0x95,0x01,
            (byte)0x81,0x06,(byte)0xc0,(byte)0xc0};

    ScrcpySession(Context context, boolean audioEnabled) {
        this.context = context.getApplicationContext();
        this.audioEnabled = audioEnabled && android.os.Build.VERSION.SDK_INT >= 30;
        ScrcpyPreferences settings = new ScrcpyPreferences(context);
        width = settings.width; height = settings.height; fps = settings.fps;
        bitrateMbps = settings.bitrateMbps; dpi = settings.dpi;
        defaultApp = settings.defaultApp;
        touchMouse = settings.touchMouse;
    }

    static ScrcpySession active() { return active; }
    float cursorX() { return cursorX; }
    float cursorY() { return cursorY; }
    boolean cursorVisible() { return cursorVisible; }
    void setCursorVisible(boolean visible) {
        if (!usesNativeMouse()) { cursorVisible = visible; return; }
        submit(() -> {
            if (visible) {
                byte[] name = "Mirra mouse".getBytes(StandardCharsets.UTF_8);
                write(ByteBuffer.allocate(10 + name.length + MOUSE_DESCRIPTOR.length)
                        .put((byte) 12).putShort((short) MOUSE_ID).putShort((short) 0).putShort((short) 0)
                        .put((byte) name.length).put(name).putShort((short) MOUSE_DESCRIPTOR.length)
                        .put(MOUSE_DESCRIPTOR).array());
            } else {
                write(ByteBuffer.allocate(3).put((byte) 14).putShort((short) MOUSE_ID).array());
            }
        });
    }
    boolean usesNativeMouse() { return !touchMouse && android.os.Build.VERSION.SDK_INT >= 35; }
    void moveMouse(float dx, float dy) {
        if (!usesNativeMouse() || stopped) return;
        synchronized (controlQueueLock) {
            float totalX = mouseRemainderX + dx * 1.5f;
            float totalY = mouseRemainderY + dy * 1.5f;
            int x = Math.round(totalX), y = Math.round(totalY);
            mouseRemainderX = totalX - x;
            mouseRemainderY = totalY - y;
            if (x == 0 && y == 0) return;
            if (queuedMouseMovement != null) {
                queuedMouseMovement.x += x;
                queuedMouseMovement.y += y;
                return;
            }
            MouseMovement movement = new MouseMovement();
            movement.x = x; movement.y = y;
            queuedMouseMovement = movement;
            try {
                controls.execute(() -> {
                    int remainingX, remainingY;
                    synchronized (controlQueueLock) {
                        if (queuedMouseMovement == movement) queuedMouseMovement = null;
                        remainingX = movement.x; remainingY = movement.y;
                    }
                    try {
                        while (!stopped && (remainingX != 0 || remainingY != 0)) {
                            int stepX = Math.max(-127, Math.min(127, remainingX));
                            int stepY = Math.max(-127, Math.min(127, remainingY));
                            writeMouseReport(mousePressed ? 1 : 0, stepX, stepY);
                            remainingX -= stepX; remainingY -= stepY;
                        }
                    } catch (Exception e) { if (!stopped) Log.w(TAG, "External display mouse input failed", e); }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                queuedMouseMovement = null;
            }
        }
    }
    private void writeMouseReport(int buttons, int dx, int dy) throws Exception {
        writeMouseReport(buttons, dx, dy, 0);
    }
    private void writeMouseReport(int buttons, int dx, int dy, int wheel) throws Exception {
        writeMouseReport(buttons, dx, dy, wheel, 0);
    }
    private void writeMouseReport(int buttons, int dx, int dy, int wheel, int horizontal) throws Exception {
        write(ByteBuffer.allocate(10).put((byte) 13).putShort((short) MOUSE_ID).putShort((short) 5)
                .put((byte) buttons).put((byte) dx).put((byte) dy).put((byte) wheel).put((byte) horizontal).array());
    }
    void setCursor(float x, float y) {
        cursorX = Math.max(0, Math.min(1, x));
        cursorY = Math.max(0, Math.min(1, y));
    }

    void start() throws Exception {
        Log.i(TAG, "Starting scrcpy " + width + "x" + height + "/" + dpi + " dpi, max fps=" + fps
                + ", bitrate=" + bitrateMbps + " Mbps, audio=" + audioEnabled
                + ", mouse=" + (usesNativeMouse() ? "virtual HID" : "injected touch"));
        byte[] serverBytes;
        try (InputStream in = context.getAssets().open("scrcpy-server.jar");
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            serverBytes = bytes.toByteArray();
        }
        String socket = "scrcpy_" + id;
        synchronized (this) {
            checkRunning();
            // One shell owns both the temporary JAR and the server. Its stdin
            // carries the JAR first, then stays open as a lifetime signal.
            server = shell("child=; watcher=; trap '[ -z \"$child\" ] || kill \"$child\" 2>/dev/null; "
                + "[ -z \"$watcher\" ] || kill \"$watcher\" 2>/dev/null; rm -f " + remoteJar + "' EXIT; "
                + "trap 'exit 1' HUP INT TERM; umask 077; head -c " + serverBytes.length + " > " + remoteJar
                + " || exit 1; chmod 644 " + remoteJar + " || exit 1; "
                + "env CLASSPATH=" + remoteJar + " app_process / com.genymobile.scrcpy.Server " + SERVER_VERSION
                + " cleanup=false scid=" + id + " tunnel_forward=true video_codec=h264 audio=" + this.audioEnabled + " control=true"
                + (this.audioEnabled ? " audio_codec=raw audio_source=output" : "")
                + " new_display=" + width + "x" + height + "/" + dpi + " max_fps=" + fps
                + " video_bit_rate=" + (bitrateMbps * 1000000) + " downsize_on_error=false"
                + " send_frame_meta=true send_dummy_byte=true send_device_meta=false send_stream_meta=false"
                + " </dev/null & child=$!; exec 3<&0;"
                + " (while IFS= read -r line; do :; done; kill \"$child\" 2>/dev/null) <&3 & watcher=$!; exec 3<&-;"
                + " wait \"$child\"; result=$?; child=; kill \"$watcher\" 2>/dev/null;"
                + " wait \"$watcher\" 2>/dev/null; watcher=; exit \"$result\"");
        }
        drainLog(server.getInputStream(), "server");
        drainLog(server.getErrorStream(), "server");
        server.getOutputStream().write(serverBytes);
        server.getOutputStream().flush();
        String apk = quote(context.getApplicationInfo().sourceDir);
        String bridge = "exec app_process -Djava.class.path=" + apk + " /system/bin "
                + ScrcpySocketBridge.class.getName() + " " + socket + " ";
        synchronized (this) { checkRunning(); videoBridge = shell(bridge + "video"); }
        drainLog(videoBridge.getErrorStream(), "video bridge");
        // The first socket accepted by scrcpy is the video socket. Wait until
        // its dummy byte arrives before opening the second (control) socket.
        DataInputStream video = new DataInputStream(videoBridge.getInputStream());
        video.readByte();
        if (stopped) throw new IllegalStateException("Casting stopped");
        DataInputStream audio = null;
        if (audioEnabled) {
            synchronized (this) { checkRunning(); audioBridge = shell(bridge + "audio"); }
            drainLog(audioBridge.getErrorStream(), "audio bridge");
            audio = new DataInputStream(audioBridge.getInputStream());
            audio.readByte(); // Bridge connected; scrcpy accepts audio before control.
        }
        synchronized (this) {
            checkRunning();
            controlBridge = shell(bridge + "control");
            drainLog(controlBridge.getErrorStream(), "control bridge");
            ScrcpyFrameRelay.start(this);
            active = this;
            videoReader = new Thread(() -> readVideo(video), "ScrcpyVideoReader");
            videoReader.start();
            if (audio != null) {
                DataInputStream audioInput = audio;
                new Thread(() -> readAudio(audioInput), "ScrcpyAudioReader").start();
            }
        }
        Log.i(TAG, "External display source started");
    }

    private void readAudio(DataInputStream input) {
        byte[] block = new byte[PlaybackAudioCapture.BLOCK_BYTES];
        int offset = 0;
        try {
            while (!stopped) {
                input.readLong();
                int length = input.readInt();
                if (length <= 0 || length > 65536 || (length & 1) != 0)
                    throw new IllegalStateException("Invalid scrcpy audio packet");
                byte[] data = new byte[length];
                input.readFully(data);
                for (int i = 0; i < length; i += 2) {
                    block[offset++] = data[i + 1];
                    block[offset++] = data[i];
                    if (offset == block.length) {
                        if (!audioBlocks.offer(block)) { audioBlocks.poll(); audioBlocks.offer(block); }
                        block = new byte[PlaybackAudioCapture.BLOCK_BYTES];
                        offset = 0;
                    }
                }
            }
        } catch (Exception e) {
            if (!stopped) Log.w(TAG, "Scrcpy audio unavailable; continuing without audio", e);
        }
    }

    byte[] nextAudioBlock() {
        byte[] block = audioBlocks.poll();
        return block == null ? new byte[PlaybackAudioCapture.BLOCK_BYTES] : block;
    }
    void clearAudioBlocks() { audioBlocks.clear(); }

    private void readVideo(DataInputStream input) {
        boolean loggedFrame = false;
        try {
            while (!stopped) {
                long meta = input.readLong();
                int size = input.readInt();
                if (size < 0 || size > 20_000_000) throw new IllegalStateException("Invalid scrcpy packet size");
                byte[] frame = new byte[size];
                input.readFully(frame);
                if (!loggedFrame) {
                    Log.i(TAG, "Received first scrcpy video packet (" + size + " bytes)");
                    loggedFrame = true;
                    startDefaultApp();
                }
                ScrcpyFrameRelay.publish(this, frame, meta & 0x1fffffffffffffffL,
                        (meta & (1L << 62)) != 0, (meta & (1L << 61)) != 0);
            }
        } catch (Exception e) {
            if (!stopped) Log.e(TAG, "External display video ended", e);
        } finally {
            if (!stopped && active == this) {
                ScrcpyFrameRelay.stop(this);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (!stopped && active == this) {
                        CastingService.stopSession(context);
                        context.stopService(new android.content.Intent(context, ProjectionService.class));
                    }
                });
            }
        }
    }

    private void startDefaultApp() {
        launchApp(defaultApp);
    }

    void launchApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        byte[] name = packageName.getBytes(StandardCharsets.UTF_8);
        if (name.length > 255) { Log.w(TAG, "App package name is too long"); return; }
        Log.i(TAG, "Requesting external-display app launch: " + packageName);
        submit(() -> write(ByteBuffer.allocate(2 + name.length).put((byte) 16)
                .put((byte) name.length).put(name).array()));
    }

    void sendTap() {
        if (usesNativeMouse()) {
            submit(() -> { writeMouseReport(1, 0, 0); writeMouseReport(0, 0, 0); });
            return;
        }
        final int x = Math.min(width - 1, Math.round(cursorX * (width - 1)));
        final int y = Math.min(height - 1, Math.round(cursorY * (height - 1)));
        submit(() -> { writeTouch(0, x, y); writeTouch(1, x, y); });
    }

    void setDragging(boolean pressed) {
        final int x = Math.round(cursorX * (width - 1));
        final int y = Math.round(cursorY * (height - 1));
        submit(() -> {
            mousePressed = pressed;
            if (usesNativeMouse()) writeMouseReport(pressed ? 1 : 0, 0, 0);
            else writeTouch(pressed ? 0 : 1, x, y);
        });
    }

    void moveLegacyDrag() {
        int x = Math.round(cursorX * (width - 1));
        int y = Math.round(cursorY * (height - 1));
        submit(() -> { if (mousePressed) writeTouch(2, x, y); });
    }

    void sendRightClick() {
        final int x = Math.round(cursorX * (width - 1));
        final int y = Math.round(cursorY * (height - 1));
        submit(() -> {
            if (usesNativeMouse()) {
                writeMouseReport(2, 0, 0); writeMouseReport(0, 0, 0);
            } else {
                writeMouseTouch(0, x, y, 2); writeMouseTouch(1, x, y, 0);
            }
        });
    }

    private void writeMouseTouch(int action, int x, int y, int buttons) throws Exception {
        write(ByteBuffer.allocate(32).put((byte) 2).put((byte) action).putLong(-1L)
                .putInt(x).putInt(y).putShort((short) width).putShort((short) height)
                .putShort((short) (action == 1 ? 0 : 0xffff)).putInt(2).putInt(buttons).array());
    }

    void zoom(int direction) {
        int x = Math.round(cursorX * (width - 1));
        int y = Math.round(cursorY * (height - 1));
        submit(() -> {
            writeKey(0, android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.META_CTRL_ON);
            if (usesNativeMouse()) writeMouseReport(0, 0, 0, direction);
            else write(ByteBuffer.allocate(21).put((byte) 3).putInt(x).putInt(y)
                    .putShort((short) width).putShort((short) height).putShort((short) 0)
                    .putShort((short) (direction * 2048)).putInt(0).array());
            writeKey(1, android.view.KeyEvent.KEYCODE_CTRL_LEFT, 0);
        });
    }

    void scroll(int horizontal, int vertical) {
        int x = Math.round(cursorX * (width - 1));
        int y = Math.round(cursorY * (height - 1));
        submit(() -> {
            int h = horizontal, v = vertical;
            while (h != 0 || v != 0) {
                int stepH = Math.max(-15, Math.min(15, h));
                int stepV = Math.max(-15, Math.min(15, v));
                if (usesNativeMouse()) writeMouseReport(0, 0, 0, stepV, stepH);
                else write(ByteBuffer.allocate(21).put((byte) 3).putInt(x).putInt(y)
                        .putShort((short) width).putShort((short) height)
                        .putShort((short) (stepH * 2048)).putShort((short) (stepV * 2048)).putInt(0).array());
                h -= stepH; v -= stepV;
            }
        });
    }

    private void writeKey(int action, int keyCode, int meta) throws Exception {
        write(ByteBuffer.allocate(14).put((byte) 0).put((byte) action)
                .putInt(keyCode).putInt(0).putInt(meta).array());
    }

    void sendKey(int keyCode) {
        submit(() -> {
            write(ByteBuffer.allocate(14).put((byte) 0).put((byte) 0).putInt(keyCode).putInt(0).putInt(0).array());
            write(ByteBuffer.allocate(14).put((byte) 0).put((byte) 1).putInt(keyCode).putInt(0).putInt(0).array());
        });
    }

    void sendKeyAction(int keyCode, boolean pressed) {
        submit(() -> writeKey(pressed ? 0 : 1, keyCode, 0));
    }

    void repeatKey(int keyCode, int repeat) {
        submit(() -> write(ByteBuffer.allocate(14).put((byte) 0).put((byte) 0)
                .putInt(keyCode).putInt(repeat).putInt(0).array()));
    }

    boolean nativeRemote() { return android.os.Build.VERSION.SDK_INT >= 35; }
    void setGamepadEnabled(boolean enabled) {
        if (!nativeRemote()) return;
        submit(() -> {
            if (enabled) {
                byte[] name = "Mirra gamepad".getBytes(StandardCharsets.UTF_8);
                write(ByteBuffer.allocate(10 + name.length + GAMEPAD_DESCRIPTOR.length)
                        .put((byte) 12).putShort((short) GAMEPAD_ID).putShort((short) 0).putShort((short) 0)
                        .put((byte) name.length).put(name).putShort((short) GAMEPAD_DESCRIPTOR.length)
                        .put(GAMEPAD_DESCRIPTOR).array());
            } else write(ByteBuffer.allocate(3).put((byte) 14).putShort((short) GAMEPAD_ID).array());
        });
    }

    void sendGamepadState(int buttons, int lx, int ly, int rx, int ry, int lt, int rt, int hat) {
        if (!nativeRemote()) return;
        submit(() -> write(ByteBuffer.allocate(14).put((byte) 13).putShort((short) GAMEPAD_ID)
                .putShort((short) 9).put((byte) buttons).put((byte) (buttons >> 8))
                .put((byte) lx).put((byte) ly).put((byte) rx).put((byte) ry)
                .put((byte) lt).put((byte) rt).put((byte) hat).array()));
    }
    void setRemoteEnabled(boolean enabled) {
        if (!nativeRemote()) return;
        submit(() -> {
            remoteKeys.clear();
            remoteConsumerKeys = 0;
            if (enabled) {
                byte[] name = "Mirra remote".getBytes(StandardCharsets.UTF_8);
                write(ByteBuffer.allocate(10 + name.length + KEYBOARD_DESCRIPTOR.length)
                        .put((byte) 12).putShort((short) REMOTE_ID).putShort((short) 0).putShort((short) 0)
                        .put((byte) name.length).put(name).putShort((short) KEYBOARD_DESCRIPTOR.length)
                        .put(KEYBOARD_DESCRIPTOR).array());
                byte[] consumerName = "Mirra remote navigation".getBytes(StandardCharsets.UTF_8);
                write(ByteBuffer.allocate(10 + consumerName.length + REMOTE_CONSUMER_DESCRIPTOR.length)
                        .put((byte) 12).putShort((short) REMOTE_CONSUMER_ID).putShort((short) 0).putShort((short) 0)
                        .put((byte) consumerName.length).put(consumerName).putShort((short) REMOTE_CONSUMER_DESCRIPTOR.length)
                        .put(REMOTE_CONSUMER_DESCRIPTOR).array());
            } else {
                write(ByteBuffer.allocate(3).put((byte) 14).putShort((short) REMOTE_ID).array());
                write(ByteBuffer.allocate(3).put((byte) 14).putShort((short) REMOTE_CONSUMER_ID).array());
            }
        });
    }
    void remoteKeyAction(int keyCode, boolean pressed) {
        if (!nativeRemote()) return;
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK || keyCode == android.view.KeyEvent.KEYCODE_HOME) {
            int bit = keyCode == android.view.KeyEvent.KEYCODE_BACK ? 1 : 2;
            submit(() -> {
                if (pressed) remoteConsumerKeys |= bit; else remoteConsumerKeys &= ~bit;
                write(ByteBuffer.allocate(6).put((byte) 13).putShort((short) REMOTE_CONSUMER_ID)
                        .putShort((short) 1).put((byte) remoteConsumerKeys).array());
            });
            return;
        }
        int usage;
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP: usage = 0x52; break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: usage = 0x51; break;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: usage = 0x50; break;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: usage = 0x4f; break;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER: usage = 0x28; break;
            default: return;
        }
        submit(() -> {
            if (pressed) remoteKeys.add(usage); else remoteKeys.remove(usage);
            byte[] report = new byte[8];
            int i = 2;
            for (int value : remoteKeys) { if (i == report.length) break; report[i++] = (byte) value; }
            write(ByteBuffer.allocate(13).put((byte) 13).putShort((short) REMOTE_ID)
                    .putShort((short) report.length).put(report).array());
        });
    }

    void requestVideoReset() {
        submit(() -> {
            write(new byte[]{17}); // scrcpy TYPE_RESET_VIDEO
            Log.i(TAG, "Requested a fresh scrcpy video stream");
        });
    }

    void turnOffPhoneDisplay() {
        // scrcpy targets the physical display for this command in new-display mode.
        submit(() -> write(new byte[]{10, 0}));
    }

    void sendText(String text) {
        submit(() -> {
            java.io.ByteArrayOutputStream chunk = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < text.length();) {
                int codePoint = text.codePointAt(i);
                byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                if (chunk.size() + encoded.length > 300) {
                    writeTextChunk(chunk.toByteArray());
                    chunk.reset();
                }
                chunk.write(encoded);
                i += Character.charCount(codePoint);
            }
            if (chunk.size() > 0) writeTextChunk(chunk.toByteArray());
        });
    }

    private void writeTextChunk(byte[] bytes) throws Exception {
        write(ByteBuffer.allocate(5 + bytes.length).put((byte) 1).putInt(bytes.length).put(bytes).array());
    }

    private void writeTouch(int action, int x, int y) throws Exception {
        write(ByteBuffer.allocate(32).put((byte) 2).put((byte) action).putLong(0)
                .putInt(x).putInt(y).putShort((short) width).putShort((short) height)
                .putShort((short) (action == 1 ? 0 : 0xffff)).putInt(1).putInt(action == 1 ? 0 : 1).array());
    }

    private interface Operation { void run() throws Exception; }
    private void submit(Operation op) {
        if (stopped) return;
        synchronized (controlQueueLock) {
            // Preserve ordering: movements after a click or drag transition must
            // never be merged into a report queued before that transition.
            queuedMouseMovement = null;
            try {
                controls.execute(() -> {
                    try { if (!stopped) op.run(); }
                    catch (Exception e) { Log.w(TAG, "External display input failed", e); }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
    }
    private void write(byte[] bytes) throws Exception {
        Process bridge = controlBridge;
        if (bridge == null) throw new IllegalStateException("Control channel not ready");
        OutputStream out = bridge.getOutputStream();
        out.write(bytes); out.flush();
    }

    private void checkRunning() {
        if (stopped) throw new IllegalStateException("Casting stopped");
    }

    private static int waitForProcess(Process process, int timeoutSeconds) throws Exception {
        // ShizukuRemoteProcess.exitValue() may throw IllegalArgumentException
        // while running, which breaks Process.waitFor(timeout)'s default polling.
        java.util.concurrent.FutureTask<Integer> wait = new java.util.concurrent.FutureTask<>(process::waitFor);
        Thread waiter = new Thread(wait, "ScrcpyProcessWait");
        waiter.setDaemon(true);
        waiter.start();
        try {
            return wait.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            process.destroy();
            throw new IllegalStateException("Timed out preparing scrcpy server", e);
        }
    }

    synchronized void stop() {
        if (stopped) return;
        Log.i(TAG, "Stopping scrcpy server and bridges");
        stopped = true;
        if (active == this) { active = null; ScrcpyFrameRelay.stop(this); }
        controls.shutdownNow();
        destroyProcess(videoBridge);
        destroyProcess(audioBridge);
        destroyProcess(controlBridge);
        Process serverProcess = server;
        new Thread(() -> {
            try {
                if (serverProcess != null) {
                    try { serverProcess.getOutputStream().close(); }
                    catch (Exception e) { Log.w(TAG, "Unable to close server lifetime pipe", e); destroyProcess(serverProcess); }
                    try { waitForProcess(serverProcess, 5); }
                    catch (Exception e) { Log.w(TAG, "Server did not stop cleanly", e); }
                }
                Process remove = shell("rm -f " + remoteJar);
                if (waitForProcess(remove, 5) != 0) Log.w(TAG, "Temporary server cleanup failed");
            } catch (Exception e) { Log.w(TAG, "Temporary server cleanup failed", e); }
        }, "ScrcpyCleanup").start();
    }

    private static void destroyProcess(Process process) {
        if (process == null) return;
        try { process.destroy(); }
        catch (RuntimeException e) { Log.w(TAG, "Unable to stop scrcpy process", e); }
    }

    private static Process shell(String command) throws Exception {
        Method method;
        try {
            method = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
                    .getMethod("newProcess", String[].class, String[].class, String.class);
        } catch (NoSuchMethodException e) {
            method = Class.forName("rikka.shizuku.Shizuku")
                    .getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        }
        method.setAccessible(true);
        return (Process) method.invoke(null, new String[]{"sh", "-c", command}, null, null);
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static void drainLog(InputStream stream, String label) {
        new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) Log.i(TAG, label + ": " + line);
            } catch (Exception ignored) { }
        }, "ScrcpyLog").start();
    }
}
