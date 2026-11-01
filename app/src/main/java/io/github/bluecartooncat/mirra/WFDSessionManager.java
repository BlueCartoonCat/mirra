package io.github.bluecartooncat.mirra;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;

/** Owns the transport and encoder for one Wi-Fi Display session. */
public class WFDSessionManager {
    private static final String TAG = "WFDSessionManager";
    private static final int RTP_PORT = 5000;
    private static final int RTCP_PORT = 5001;

    private RTPStreamer streamer;
    private VideoNegotiator.Selection videoSelection;
    private DatagramSocket rtpSocket;
    private DatagramSocket rtcpSocket;
    private String clientAddress;
    private int clientRtpPort = 16384;
    private boolean audioEnabled;
    private final boolean captureAudio;

    public WFDSessionManager(boolean captureAudio) {
        this.captureAudio = captureAudio;
    }

    /** Reply to optional parameter requests and RTSP keepalives from the sink. */
    public String getParameters(String requestBody) {
        StringBuilder response = new StringBuilder();
        for (String line : requestBody.split("\\r?\\n")) {
            switch (line.trim()) {
                case "wfd_video_formats":
                    response.append("wfd_video_formats: 00 00 01 01 00000081 00000000 00000000 00 0000 0000 00 none none\r\n");
                    break;
                case "wfd_audio_codecs": response.append("wfd_audio_codecs: none\r\n"); break;
                case "wfd_client_rtp_ports": response.append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast 16384 0 mode=play\r\n"); break;
                case "wfd_display_edid": response.append("wfd_display_edid: none\r\n"); break;
                case "wfd_content_protection": response.append("wfd_content_protection: none\r\n"); break;
                case "wfd_3d_video_formats": response.append("wfd_3d_video_formats: none\r\n"); break;
                case "wfd_coupled_sink": response.append("wfd_coupled_sink: none\r\n"); break;
                case "wfd_uibc_capability": response.append("wfd_uibc_capability: none\r\n"); break;
                case "wfd_connector_type": response.append("wfd_connector_type: 07\r\n"); break;
                case "wfd_standby_resume_capability": response.append("wfd_standby_resume_capability: none\r\n"); break;
                default: break;
            }
        }
        return response.toString();
    }

    /** The receiver supplies its UDP port in its capability response. */
    public void setParameters(String requestBody) {
        for (String line : requestBody.split("\\r?\\n")) {
            if (!line.trim().startsWith("wfd_client_rtp_ports:")) continue;
            String[] parts = line.substring(line.indexOf(':') + 1).trim().split("\\s+");
            if (parts.length < 2) continue;
            try {
                clientRtpPort = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid receiver RTP port", e);
            }
        }
    }

    public void setClientAddress(String address) { clientAddress = address; }
    public void setAudioEnabled(boolean enabled) { audioEnabled = enabled; }

    public void setVideoFormat(VideoNegotiator.Selection selection) {
        videoSelection = selection;
        Log.i(TAG, "Selected video: " + selection.width + "x" + selection.height
                + "p" + selection.fps + " encoder=" + selection.codecName);
    }

    /** Reserve advertised source ports before acknowledging SETUP. */
    public synchronized void prepareTransport() throws IOException {
        if (rtpSocket != null && !rtpSocket.isClosed()) return;
        rtpSocket = new DatagramSocket(RTP_PORT);
        try {
            rtcpSocket = new DatagramSocket(RTCP_PORT);
        } catch (IOException e) {
            rtpSocket.close();
            rtpSocket = null;
            throw e;
        }
    }

    public boolean startStreaming() {
        if (clientAddress == null || videoSelection == null) {
            Log.e(TAG, "Cannot start: receiver or video format is missing");
            return false;
        }
        if (streamer == null) {
            streamer = new RTPStreamer(clientAddress, clientRtpPort, videoSelection,
                    rtpSocket, rtcpSocket, audioEnabled, captureAudio);
            // The streamer owns the sockets after construction.
            rtpSocket = null;
            rtcpSocket = null;
        }
        return streamer.start();
    }

    public void pauseStreaming() {
        if (streamer != null) {
            streamer.stop();
            streamer = null;
        }
    }

    public void stopStreaming() {
        pauseStreaming();
        if (rtpSocket != null) { rtpSocket.close(); rtpSocket = null; }
        if (rtcpSocket != null) { rtcpSocket.close(); rtcpSocket = null; }
    }
}
