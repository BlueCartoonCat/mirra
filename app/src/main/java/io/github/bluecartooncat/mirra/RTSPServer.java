package io.github.bluecartooncat.mirra;


import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RTSPServer {
    private static final String TAG = "RTSPServer";
    private static final int RTSP_PORT = 7236;
    
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean isRunning = false;
    private WFDSessionManager sessionManager;
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<>());
    private final InetAddress bindAddress;
    private final Runnable onBindFailed;
    
    public RTSPServer(InetAddress bindAddress, boolean captureAudio, boolean externalDisplay, int disabledModes, boolean forceSinkLevels,
                      java.util.function.Consumer<VideoNegotiator.Selection> onPlaybackStarted,
                      Runnable onNoCompatibleVideo, Runnable onBindFailed) {
        if (bindAddress == null || bindAddress.isAnyLocalAddress() || bindAddress.isLoopbackAddress())
            throw new IllegalArgumentException("A specific Wi-Fi Direct address is required");
        this.bindAddress = bindAddress;
        this.onBindFailed = onBindFailed;
        executorService = Executors.newCachedThreadPool();
        sessionManager = new WFDSessionManager(captureAudio, externalDisplay, disabledModes, forceSinkLevels, onPlaybackStarted);
        this.onNoCompatibleVideo = onNoCompatibleVideo;
    }
    private final Runnable onNoCompatibleVideo;
    
    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        
        executorService.execute(() -> {
            try {
                ServerSocket listeningSocket = new ServerSocket(RTSP_PORT, 50, bindAddress);
                synchronized (this) {
                    if (!isRunning) {
                        listeningSocket.close();
                        return;
                    }
                    serverSocket = listeningSocket;
                }
                Log.i(TAG, "RTSP Server started on " + bindAddress.getHostAddress() + ":" + RTSP_PORT);
                
                while (isRunning && !Thread.currentThread().isInterrupted()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        Log.i(TAG, "Client connected: " + clientSocket.getInetAddress());
                        handleClient(clientSocket);
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting client", e);
                        }
                    }
                }
            } catch (IOException e) {
                boolean notify = isRunning;
                if (notify) Log.e(TAG, "Failed to start RTSP server", e);
                isRunning = false;
                if (notify && onBindFailed != null) onBindFailed.run();
            }
        });
    }
    
    private void handleClient(Socket socket) {
        clients.add(socket);
        executorService.execute(() -> {
            try { new RTSPClientHandler(socket, sessionManager, onNoCompatibleVideo).run(); }
            finally { clients.remove(socket); }
        });
    }
    
    public synchronized void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket", e);
        }
        synchronized (clients) {
            for (Socket client : clients) try { client.close(); } catch (IOException ignored) { }
        }
        sessionManager.stopStreaming();
        executorService.shutdown();
    }
    
    private static class RTSPClientHandler implements Runnable {
        private final Socket socket;
        private final WFDSessionManager sessionManager;
        private final Runnable onNoCompatibleVideo;
        private BufferedReader reader;
        private DataOutputStream writer;
        private int cseq = 0;
        private int pendingCseq;
        private String pendingMethod;
        private boolean optionsResponse;
        private boolean sinkOptions;
        private boolean capabilitiesRequested;
        private int sinkRtpPort;
        private String sessionId;
        private String selectedCea = "00000001";
        private String selectedLevel = "01";
        private String selectedProfile = "01";
        private String selectedAudio = "none";
        private final Object writeLock = new Object();
        private final Set<String> keepaliveRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private java.util.concurrent.ScheduledExecutorService keepalive;
        private volatile boolean tornDown;
        
        public RTSPClientHandler(Socket socket, WFDSessionManager sessionManager, Runnable onNoCompatibleVideo) {
            this.socket = socket;
            this.sessionManager = sessionManager;
            this.onNoCompatibleVideo = onNoCompatibleVideo;
        }
        
        @Override
        public void run() {
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new DataOutputStream(socket.getOutputStream());
                sessionManager.setClientAddress(socket.getInetAddress().getHostAddress());
                
                // Send initial OPTIONS request to establish WFD support
                sendInitialOptions();
                
                while (!Thread.currentThread().isInterrupted()) {
                    RTSPRequest request = parseRequest();
                    if (request == null) break;
                    
                    if (request.method.startsWith("RTSP/")) {
                        Log.i(TAG, "Received RTSP response: " + request.method + " "
                                + request.uri + " " + request.version);
                        handleResponse(request);
                    } else {
                        handleRequest(request);
                        if (tornDown) break;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Client handler error", e);
            } finally {
                stopKeepalive();
                sessionManager.stopStreaming();
                try {
                    socket.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing client socket", e);
                }
            }
        }
        
        private void sendInitialOptions() throws IOException {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            String request = "OPTIONS * RTSP/1.0\r\n" +
                    "CSeq: " + (++cseq) + "\r\n" +
                    "Require: org.wfa.wfd1.0\r\n" +
                    "\r\n";
            
            writer.write(request.getBytes());
            writer.flush();
            pendingCseq = cseq;
            pendingMethod = "OPTIONS";
            Log.d(TAG, "Sent initial OPTIONS request");
        }
        
        private RTSPRequest parseRequest() throws IOException {
            String requestLine = reader.readLine();
            if (requestLine == null) return null;
            
            Log.i(TAG, "RTSP " + requestLine);
            
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String key = line.substring(0, colonIdx).trim();
                    String value = line.substring(colonIdx + 1).trim();
                    headers.put(key, value);
                }
            }
            
            String body = null;
            if (headers.containsKey("Content-Length")) {
                int contentLength = Integer.parseInt(headers.get("Content-Length"));
                if (contentLength > 0) {
                    char[] buffer = new char[contentLength];
                    int offset = 0;
                    while (offset < contentLength) {
                        int count = reader.read(buffer, offset, contentLength - offset);
                        if (count < 0) throw new EOFException("Incomplete RTSP body");
                        offset += count;
                    }
                    body = new String(buffer);
                }
            }
            
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) return null;
            
            return new RTSPRequest(parts[0], parts[1], parts[2], headers, body);
        }
        
        private void handleRequest(RTSPRequest request) throws IOException {
            String method = request.method;
            String cseqStr = request.headers.get("CSeq");
            
            if ("OPTIONS".equals(method)) {
                handleOptions(request, cseqStr);
            } else if ("GET_PARAMETER".equals(method)) {
                handleGetParameter(request, cseqStr);
            } else if ("SET_PARAMETER".equals(method)) {
                handleSetParameter(request, cseqStr);
            } else if ("SETUP".equals(method)) {
                handleSetup(request, cseqStr);
            } else if ("PLAY".equals(method)) {
                handlePlay(request, cseqStr);
            } else if ("PAUSE".equals(method)) {
                handlePause(request, cseqStr);
            } else if ("TEARDOWN".equals(method)) {
                handleTeardown(request, cseqStr);
            } else {
                sendResponse("501 Not Implemented", cseqStr, null);
            }
        }
        
        private void handleOptions(RTSPRequest request, String cseq) throws IOException {
            String publicMethods = "org.wfa.wfd1.0, OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN";
            Map<String, String> headers = new HashMap<>();
            headers.put("Public", publicMethods);
            sendResponse("200 OK", cseq, headers);
            sinkOptions = true;
            maybeRequestCapabilities();
        }

        private void maybeRequestCapabilities() throws IOException {
            if (!optionsResponse || !sinkOptions || capabilitiesRequested) return;
            capabilitiesRequested = true;
            sendParameterRequest("GET_PARAMETER", "wfd_video_formats\r\nwfd_audio_codecs\r\n"
                    + "wfd_client_rtp_ports\r\nwfd_content_protection\r\n");
        }

        private void sendParameterRequest(String method, String body) throws IOException {
            synchronized (writeLock) {
            pendingCseq = ++cseq;
            pendingMethod = method;
            String wireMethod = "TRIGGER_SETUP".equals(method) ? "SET_PARAMETER" : method;
            String request = wireMethod + " rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: " + cseq
                    + "\r\nContent-Type: text/parameters\r\nContent-Length: "
                    + body.getBytes(java.nio.charset.StandardCharsets.US_ASCII).length + "\r\n\r\n" + body;
            writer.write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            writer.flush();
            Log.i(TAG, "Sent " + method + " CSeq=" + cseq);
            }
        }

        private void handleResponse(RTSPRequest response) throws IOException {
            String responseCseq = response.headers.get("CSeq");
            if (responseCseq != null && keepaliveRequests.remove(responseCseq)) {
                if (!"200".equals(response.uri)) Log.w(TAG, "RTSP keepalive rejected: " + response.uri);
                return;
            }
            if (!Integer.toString(pendingCseq).equals(response.headers.get("CSeq"))) {
                Log.w(TAG, "Ignoring unexpected response CSeq=" + response.headers.get("CSeq"));
                return;
            }
            if (!"200".equals(response.uri)) throw new IOException("Sink rejected " + pendingMethod + ": " + response.uri);
            String method = pendingMethod;
            pendingMethod = null;
            if ("OPTIONS".equals(method)) {
                optionsResponse = true;
                maybeRequestCapabilities();
            } else if ("GET_PARAMETER".equals(method)) {
                if (response.body == null) throw new IOException("Sink did not provide capabilities");
                sessionManager.setParameters(response.body);
                java.util.regex.Matcher audio = java.util.regex.Pattern.compile("(?im)^wfd_audio_codecs:[ \\t]*([^\\r\\n]+)").matcher(response.body);
                if (audio.find()) {
                    Log.i(TAG, "Receiver audio capabilities: " + audio.group(1));
                    java.util.regex.Matcher lpcm = java.util.regex.Pattern.compile("(?i)\\bLPCM\\s+([0-9a-f]+)").matcher(audio.group(1));
                    if (lpcm.find() && (Long.parseLong(lpcm.group(1), 16) & 2) != 0) {
                        selectedAudio = "LPCM 00000002 00";
                        sessionManager.setAudioEnabled(true);
                        Log.i(TAG, "Selected 48 kHz stereo LPCM for playback audio");
                    }
                }
                java.util.regex.Matcher ports = java.util.regex.Pattern.compile("(?im)^wfd_client_rtp_ports:\\s*\\S+\\s+(\\d+)").matcher(response.body);
                if (!ports.find()) throw new IOException("Sink did not provide RTP port");
                sinkRtpPort = Integer.parseInt(ports.group(1));
                if (sinkRtpPort < 1 || sinkRtpPort > 65534) throw new IOException("Invalid sink RTP port " + sinkRtpPort);
                java.util.regex.Matcher video = java.util.regex.Pattern.compile("(?im)^wfd_video_formats:[ \\t]*([^\\r\\n]+)").matcher(response.body);
                if (!video.find()) throw new IOException("Sink did not provide video formats");
                Log.i(TAG, "Receiver video capabilities: " + video.group(1) + "; RTP port=" + sinkRtpPort);
                VideoNegotiator.Selection selection;
                try {
                    selection = sessionManager.selectVideoFormat(video.group(1));
                } catch (IOException e) {
                    if (onNoCompatibleVideo != null) onNoCompatibleVideo.run();
                    throw e;
                }
                selectedCea = VideoNegotiator.hex(1 << selection.ceaBit, 8);
                selectedLevel = VideoNegotiator.hex(selection.wfdLevel, 2);
                selectedProfile = VideoNegotiator.hex(selection.wfdProfile, 2);
                sendSetParameter();
            } else if ("SET_PARAMETER".equals(method)) {
                sendTriggerSetup();
            }
        }
        
        private void handleGetParameter(RTSPRequest request, String cseq) throws IOException {
            if (request.body != null) {
                String responseBody = sessionManager.getParameters(request.body);
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "text/parameters");
                sendResponse("200 OK", cseq, headers, responseBody);
            } else {
                sendResponse("200 OK", cseq, null);
            }
        }
        
        private void sendSetParameter() throws IOException {
            String localIp = socket.getLocalAddress().getHostAddress();
            String presentationUrl = "rtsp://" + localIp + ":" + RTSP_PORT + "/wfd1.0/streamid=0";
            
            String body = "wfd_video_formats: 00 00 " + selectedProfile + " " + selectedLevel + " " + selectedCea + " 00000000 00000000 00 0000 0000 00 none none\r\n" +
                    "wfd_audio_codecs: " + selectedAudio + "\r\n" +
                    "wfd_presentation_URL: " + presentationUrl + " none\r\n" +
                    "wfd_client_rtp_ports: RTP/AVP/UDP;unicast " + sinkRtpPort + " 0 mode=play\r\n";
            
            sendParameterRequest("SET_PARAMETER", body);
        }
        
        private void handleSetParameter(RTSPRequest request, String cseq) throws IOException {
            if (request.body != null) {
                sessionManager.setParameters(request.body);
            }
            sendResponse("200 OK", cseq, null);
            
        }
        
        private void sendTriggerSetup() throws IOException {
            String body = "wfd_trigger_method: SETUP\r\n";
            
            sendParameterRequest("TRIGGER_SETUP", body);
            Log.d(TAG, "Sent TRIGGER SETUP request");
        }
        
        private void handleSetup(RTSPRequest request, String cseq) throws IOException {
            String transport = request.headers.get("Transport");
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("client_port=(\\d+)(?:-(\\d+))?", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(transport == null ? "" : transport);
            if (!match.find() || Integer.parseInt(match.group(1)) != sinkRtpPort) {
                sendResponse("461 Unsupported Transport", cseq, null);
                return;
            }
            try {
                sessionManager.prepareTransport();
            } catch (IOException e) {
                Log.e(TAG, "Cannot bind RTP transport", e);
                sendResponse("453 Not Enough Bandwidth", cseq, null);
                return;
            }
            sessionId = Long.toString(new java.security.SecureRandom().nextInt(Integer.MAX_VALUE - 1) + 1L);
            Map<String, String> headers = new HashMap<>();
            headers.put("Session", sessionId + ";timeout=30");
            String clientPorts = match.group(1) + (match.group(2) == null ? "" : "-" + match.group(2));
            headers.put("Transport", "RTP/AVP/UDP;unicast;client_port=" + clientPorts + ";server_port=5000-5001");
            sendResponse("200 OK", cseq, headers);
        }
        
        private void handlePlay(RTSPRequest request, String cseq) throws IOException {
            String requestedSession = request.headers.get("Session");
            Map<String, String> headers = new HashMap<>();
            if (requestedSession != null) {
                headers.put("Session", requestedSession);
            }
            if (sessionId == null || !sessionId.equals(sessionIdOnly(requestedSession))) {
                sendResponse("454 Session Not Found", cseq, null);
            } else if (!sessionManager.startStreaming()) {
                sendResponse("503 Service Unavailable", cseq, null);
            } else {
                sendResponse("200 OK", cseq, headers);
                sessionManager.notifyPlaybackStarted();
                startKeepalive();
            }
        }

        private void startKeepalive() {
            if (keepalive != null) return;
            keepalive = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            keepalive.scheduleAtFixedRate(() -> {
                try {
                    synchronized (writeLock) {
                        int sequence = ++cseq;
                        keepaliveRequests.add(Integer.toString(sequence));
                        String request = "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: "
                                + sequence + "\r\nSession: " + sessionId + "\r\nContent-Length: 0\r\n\r\n";
                        writer.write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        writer.flush();
                    }
                } catch (IOException e) {
                    Log.w(TAG, "RTSP keepalive failed", e);
                    try { socket.close(); } catch (IOException ignored) { }
                }
            }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
        }

        private void stopKeepalive() {
            if (keepalive != null) { keepalive.shutdownNow(); keepalive = null; }
            keepaliveRequests.clear();
        }

        private String sessionIdOnly(String value) { return value == null ? null : value.split(";", 2)[0].trim(); }
        
        private void handlePause(RTSPRequest request, String cseq) throws IOException {
            stopKeepalive();
            String sessionId = request.headers.get("Session");
            Map<String, String> headers = new HashMap<>();
            if (sessionId != null) {
                headers.put("Session", sessionId);
            }
            sendResponse("200 OK", cseq, headers);
            
            sessionManager.pauseStreaming();
        }
        
        private void handleTeardown(RTSPRequest request, String cseq) throws IOException {
            tornDown = true;
            stopKeepalive();
            sendResponse("200 OK", cseq, null);
            sessionManager.stopStreaming();
        }
        
        private void sendResponse(String status, String cseq, Map<String, String> headers) throws IOException {
            sendResponse(status, cseq, headers, null);
        }
        
        private void sendResponse(String status, String cseq, Map<String, String> headers, String body) throws IOException {
            StringBuilder response = new StringBuilder();
            response.append("RTSP/1.0 ").append(status).append("\r\n");
            response.append("CSeq: ").append(cseq).append("\r\n");
            
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    response.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
                }
            }
            
            if (body != null) {
                response.append("Content-Length: ").append(body.length()).append("\r\n");
                response.append("\r\n");
                response.append(body);
            } else {
                response.append("\r\n");
            }
            
            synchronized (writeLock) {
                writer.write(response.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                writer.flush();
            }
            Log.d(TAG, "Sent response: " + status);
        }
    }
    
    private static class RTSPRequest {
        String method;
        String uri;
        String version;
        Map<String, String> headers;
        String body;
        
        RTSPRequest(String method, String uri, String version, Map<String, String> headers, String body) {
            this.method = method;
            this.uri = uri;
            this.version = version;
            this.headers = headers;
            this.body = body;
        }
    }
}
