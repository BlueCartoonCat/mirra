package io.github.bluecartooncat.mirra;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** H.264 surface encoder and minimal MPEG-TS/RTP (MP2T payload type 33) sender. */
public class RTPStreamer {
    private static final String TAG = "RTPStreamer";
    private final String clientIp;
    private final int clientPort;
    private final int width, height, frameRate;
    private final VideoNegotiator.Selection selection;
    private final boolean audioEnabled;
    private final boolean captureAudio;
    private PlaybackAudioCapture audioCapture;
    private long nextAudioUs = -1;
    private final AtomicBoolean running = new AtomicBoolean();
    private final int[] continuity = new int[8192];
    private final int ssrc = new java.security.SecureRandom().nextInt();
    private int sequence;
    private MediaProjection projection;
    private MediaCodec encoder;
    private Surface surface;
    private ProjectionRenderer renderer;
    private DatagramSocket socket;
    private DatagramSocket rtcpSocket;
    private long rtpPackets, rtpOctets, lastReportNanos;
    private Thread worker;
    private byte[] codecConfig;

    public RTPStreamer(String ip, int port, VideoNegotiator.Selection selection,
                       DatagramSocket rtpSocket, DatagramSocket rtcpSocket, boolean audioEnabled, boolean captureAudio) {
        clientIp = ip; clientPort = port;
        this.selection = selection;
        this.socket = rtpSocket; this.rtcpSocket = rtcpSocket; this.audioEnabled = audioEnabled;
        this.captureAudio = captureAudio;
        this.width = selection.width; this.height = selection.height; this.frameRate = selection.fps;
    }

    public synchronized boolean start() {
        if (running.get()) return true;
        projection = ProjectionService.takeProjection();
        if (projection == null) {
            Log.e(TAG, "No screen capture permission: tap Allow screen capture before connecting");
            return false;
        }
        try {
            if (socket == null || rtcpSocket == null || socket.isClosed() || rtcpSocket.isClosed())
                throw new IllegalStateException("RTP transport not prepared");
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            int bitRate = width >= 7680 ? 60000000 : width >= 3840 ? 30000000
                    : width >= 2560 ? 20000000 : width >= 1920 ? 12000000
                    : width >= 1280 ? 8000000 : 3000000;
            encoder = MediaCodec.createByCodecName(selection.codecName);
            bitRate = encoder.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .getVideoCapabilities().getBitrateRange().clamp(bitRate);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_PROFILE, selection.codecProfile);
            format.setInteger(MediaFormat.KEY_LEVEL, selection.codecLevel);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = encoder.createInputSurface();
            encoder.start();
            if (audioEnabled && captureAudio) {
                audioCapture = new PlaybackAudioCapture(projection);
                if (!audioCapture.start()) audioCapture = null;
            }
            // Render continuously, including when projection has no new frame.
            renderer = new ProjectionRenderer(projection, surface, width, height, frameRate,
                    ProjectionService.getDisplayDensity());
            renderer.start();
            running.set(true);
            worker = new Thread(this::drain, "MiracastEncoder");
            worker.start();
            Log.i(TAG, "Streaming " + width + "x" + height + "p" + frameRate + " H.264 MPEG-TS/RTP to " + clientIp + ":" + clientPort);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Unable to start screen streaming", e);
            cleanup();
            return false;
        }
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastFrameNanos = System.nanoTime();
        long lastStallLog = lastFrameNanos;
        try {
            InetAddress destination = InetAddress.getByName(clientIp);
            while (running.get()) {
                int index = encoder.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = encoder.getOutputFormat();
                    ByteArrayOutputStream config = new ByteArrayOutputStream();
                    for (String key : new String[]{"csd-0", "csd-1"}) {
                        ByteBuffer b = f.getByteBuffer(key);
                        if (b != null) { byte[] data = new byte[b.remaining()]; b.get(data); config.write(data); }
                    }
                    codecConfig = config.toByteArray();
                    Log.i(TAG, "Encoder format: " + f);
                } else if (index >= 0) {
                    lastFrameNanos = System.nanoTime();
                    ByteBuffer b = encoder.getOutputBuffer(index);
                    if (b != null && info.size > 0) {
                        b.position(info.offset); b.limit(info.offset + info.size);
                        byte[] data = new byte[info.size]; b.get(data);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) codecConfig = data;
                        else {
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            ByteArrayOutputStream accessUnit = new ByteArrayOutputStream();
                            accessUnit.write(new byte[]{0, 0, 0, 1, 9, (byte) 0xf0});
                            if (key && codecConfig != null) accessUnit.write(codecConfig);
                            accessUnit.write(data);
                            sendAccessUnit(destination, accessUnit.toByteArray(), info.presentationTimeUs, key);
                        }
                    }
                    encoder.releaseOutputBuffer(index, false);
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    long now = System.nanoTime();
                    if (now - lastFrameNanos > 3_000_000_000L && now - lastStallLog > 5_000_000_000L) {
                        Log.w(TAG, "Encoder has produced no frames for " + ((now - lastFrameNanos) / 1_000_000_000L) + " seconds");
                        lastStallLog = now;
                    }
                }
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "Streaming failed", e);
        } finally { running.set(false); cleanup(); }
    }

    private void sendAccessUnit(InetAddress dest, byte[] data, long us, boolean key) throws Exception {
        long pts = (us * 90 / 1000) & 0x1ffffffffL;
        ByteArrayOutputStream packets = new ByteArrayOutputStream();
        if (key) {
            section(packets, 0, new byte[]{0, (byte) 0xb0, 13, 0, 1, (byte) 0xc1, 0, 0, 0, 1, (byte) 0xe1, 0});
            if (audioEnabled) {
                section(packets, 256, new byte[]{2, (byte) 0xb0, 23, 0, 1, (byte) 0xc1, 0, 0,
                        (byte) 0xe1, 1, (byte) 0xf0, 0, 0x1b, (byte) 0xe1, 1, (byte) 0xf0, 0,
                        (byte) 0x83, (byte) 0xe1, 2, (byte) 0xf0, 0});
            } else {
                section(packets, 256, new byte[]{2, (byte) 0xb0, 18, 0, 1, (byte) 0xc1, 0, 0, (byte) 0xe1, 1, (byte) 0xf0, 0, 0x1b, (byte) 0xe1, 1, (byte) 0xf0, 0});
            }
        }
        ByteArrayOutputStream pes = new ByteArrayOutputStream();
        pes.write(new byte[]{0, 0, 1, (byte) 0xe0, 0, 0, (byte) 0x80, (byte) 0x80, 5,
                (byte) (0x21 | ((pts >> 29) & 0x0e)), (byte) (pts >> 22),
                (byte) (1 | ((pts >> 14) & 0xfe)), (byte) (pts >> 7), (byte) (1 | ((pts << 1) & 0xfe))});
        pes.write(data);
        packetize(packets, 257, pes.toByteArray(), true, pts);
        if (audioEnabled) {
            if (nextAudioUs < 0) {
                nextAudioUs = us;
                if (audioCapture != null) audioCapture.clearBufferedAudio();
            } else if (us - nextAudioUs > 100_000) {
                nextAudioUs = us;
                if (audioCapture != null) audioCapture.clearBufferedAudio();
            }
            while (nextAudioUs <= us) {
                long audioPts = (nextAudioUs * 90 / 1000) & 0x1ffffffffL;
                ByteArrayOutputStream audio = new ByteArrayOutputStream();
                byte[] pcm = audioCapture == null ? new byte[PlaybackAudioCapture.BLOCK_BYTES] : audioCapture.nextBlock();
                int pcmBytes = pcm.length;
                int pesLength = 8 + 4 + pcmBytes;
                audio.write(new byte[]{0, 0, 1, (byte) 0xbd, (byte) (pesLength >> 8), (byte) pesLength,
                        (byte) 0x80, (byte) 0x80, 5, (byte) (0x21 | ((audioPts >> 29) & 0x0e)),
                        (byte) (audioPts >> 22), (byte) (1 | ((audioPts >> 14) & 0xfe)),
                        (byte) (audioPts >> 7), (byte) (1 | ((audioPts << 1) & 0xfe)),
                        (byte) (pcmBytes >> 8), (byte) pcmBytes, 0x31, 0x40});
                audio.write(pcm);
                packetize(packets, 258, audio.toByteArray(), false, audioPts);
                nextAudioUs += 10_000;
            }
        }
        byte[] ts = packets.toByteArray();
        for (int offset = 0; offset < ts.length; offset += 7 * 188) {
            int len = Math.min(7 * 188, ts.length - offset);
            byte[] rtp = new byte[12 + len];
            rtp[0] = (byte) 0x80; rtp[1] = 33;
            rtp[2] = (byte) (sequence >> 8); rtp[3] = (byte) sequence++;
            for (int i = 0; i < 4; i++) { rtp[4 + i] = (byte) (pts >> (24 - 8 * i)); rtp[8 + i] = (byte) (ssrc >> (24 - 8 * i)); }
            System.arraycopy(ts, offset, rtp, 12, len);
            socket.send(new DatagramPacket(rtp, rtp.length, dest, clientPort));
            rtpPackets++;
            rtpOctets += len;
        }
        long now = System.nanoTime();
        if (now - lastReportNanos >= 5_000_000_000L) {
            sendSenderReport(dest, pts);
            lastReportNanos = now;
        }
    }

    private void sendSenderReport(InetAddress dest, long rtpTimestamp) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        // Compound RTCP packet: sender report followed by an SDES CNAME.
        long millis = System.currentTimeMillis();
        long ntpSeconds = millis / 1000L + 2208988800L;
        long ntpFraction = ((millis % 1000L) << 32) / 1000L;
        out.writeByte(0x80); out.writeByte(200); out.writeShort(6);
        out.writeInt(ssrc); out.writeInt((int) ntpSeconds); out.writeInt((int) ntpFraction);
        out.writeInt((int) rtpTimestamp); out.writeInt((int) rtpPackets); out.writeInt((int) rtpOctets);
        byte[] cname = "mirra".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int chunkLength = (4 + 2 + cname.length + 1 + 3) & ~3;
        out.writeByte(0x81); out.writeByte(202); out.writeShort(chunkLength / 4);
        out.writeInt(ssrc); out.writeByte(1); out.writeByte(cname.length); out.write(cname); out.writeByte(0);
        for (int i = 4 + 2 + cname.length + 1; i < chunkLength; i++) out.writeByte(0);
        byte[] packet = bytes.toByteArray();
        rtcpSocket.send(new DatagramPacket(packet, packet.length, dest, clientPort + 1));
    }

    private void section(ByteArrayOutputStream out, int pid, byte[] section) throws Exception {
        int crc = 0xffffffff;
        for (byte b : section) {
            crc ^= (b & 255) << 24;
            for (int i = 0; i < 8; i++) crc = (crc << 1) ^ ((crc & 0x80000000) != 0 ? 0x04c11db7 : 0);
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0); payload.write(section);
        for (int i = 3; i >= 0; i--) payload.write(crc >> (8 * i));
        packetize(out, pid, payload.toByteArray(), false, 0);
    }

    private void packetize(ByteArrayOutputStream out, int pid, byte[] payload, boolean pcr, long pts) {
        int pos = 0;
        while (pos < payload.length) {
            boolean first = pos == 0;
            int minAdapt = first && pcr ? 8 : 0;
            int n = Math.min(184 - minAdapt, payload.length - pos);
            int adapt = 184 - n;
            byte[] ts = new byte[188]; Arrays.fill(ts, (byte) 0xff);
            ts[0] = 0x47; ts[1] = (byte) ((first ? 0x40 : 0) | (pid >> 8)); ts[2] = (byte) pid;
            ts[3] = (byte) ((adapt > 0 ? 0x30 : 0x10) | (continuity[pid]++ & 15));
            if (adapt > 0) {
                ts[4] = (byte) (adapt - 1);
                if (adapt > 1) ts[5] = (byte) (first && pcr ? 0x10 : 0);
                if (first && pcr) {
                    ts[6] = (byte) (pts >> 25); ts[7] = (byte) (pts >> 17); ts[8] = (byte) (pts >> 9);
                    ts[9] = (byte) (pts >> 1); ts[10] = (byte) (((pts & 1) << 7) | 0x7e); ts[11] = 0;
                }
            }
            System.arraycopy(payload, pos, ts, 4 + adapt, n); pos += n;
            out.write(ts, 0, ts.length);
        }
    }

    public void stop() {
        running.set(false);
        if (socket != null) socket.close();
        if (rtcpSocket != null) rtcpSocket.close();
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private synchronized void cleanup() {
        if (audioCapture != null) { audioCapture.stop(); audioCapture = null; }
        if (renderer != null) { renderer.stop(); renderer = null; }
        if (encoder != null) { try { encoder.stop(); } catch (Exception ignored) {} encoder.release(); encoder = null; }
        if (surface != null) { surface.release(); surface = null; }
        if (socket != null) { socket.close(); socket = null; }
        if (rtcpSocket != null) { rtcpSocket.close(); rtcpSocket = null; }
        if (projection != null) { projection.stop(); projection = null; }
    }
}
