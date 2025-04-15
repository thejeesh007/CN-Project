import java.io.*;
import java.net.*;
import java.util.*;
import java.util.HashSet;
import java.util.Set;

class VideoRelay {
    private static final int SERVER_PORT = 5000;
    private static final int BUFFER_SIZE = 100;
    private static final int TIMEOUT = 10000;

    private static final double ADDITIVE_INCREASE = 1.0;
    private static final double MULTIPLICATIVE_DECREASE = 0.5;
    private static final int INITIAL_CWND = 1;
    private static final int INITIAL_SSTHRESH = 16;

    private static double cwnd = INITIAL_CWND;
    private static double ssthresh = INITIAL_SSTHRESH;
    private static long lastRTT = 50; // Initial RTT estimate in ms
    private static final int BASE_BITRATE = 500; // Base bitrate in kbps
    private static final int MAX_BITRATE = 4000; // Max bitrate in kbps

    // LRU Cache for video frames (each video is cached by a LinkedHashMap mapping frame index → byte[]).
    // The key of the outer map is the video name.
    public static final Map<String, LinkedHashMap<Integer, byte[]>> relayCache = new HashMap<>();

    public static void main(String[] args) throws IOException {
        Socket serverSocket = new Socket("127.0.0.1", SERVER_PORT); // Linux-compatible IP
        System.out.println("DEBUG (Relay): Connected to server at port " + SERVER_PORT);

        ServerSocket relayServerSocket = new ServerSocket(0);
        int relayPort = relayServerSocket.getLocalPort();
        System.out.println("DEBUG (Relay): Listening on port: " + relayPort);

        while (true) {
            Socket clientSocket = relayServerSocket.accept();
            clientSocket.setSoTimeout(TIMEOUT);
            System.out.println("DEBUG (Relay): New client connected!");

            Thread clientHandler = new Thread(new ClientHandler(clientSocket, serverSocket));
            clientHandler.start();
        }
    }

    private static synchronized void onSuccessfulTransmission(long rtt) {
        lastRTT = (long)((lastRTT * 0.875) + (rtt * 0.125)); // Smoothed RTT
        if (cwnd < ssthresh) {
            cwnd += ADDITIVE_INCREASE; // Slow start
        } else {
            cwnd += 1.0 / cwnd; // Congestion avoidance (AIMD)
        }
        System.out.println("DEBUG (Relay): Successful transmission, cwnd increased to " + cwnd);
    }

    private static synchronized void onPacketLoss() {
        ssthresh = Math.max((int)(cwnd / 2), 2);
        cwnd = Math.max(1, cwnd * MULTIPLICATIVE_DECREASE);
        System.out.println("DEBUG (Relay): Packet loss detected, cwnd reduced to " + cwnd + ", ssthresh set to " + ssthresh);
    }

    private static class ClientHandler implements Runnable {
        private Socket clientSocket, serverSocket;
        private DataInputStream inFromServer;
        private DataOutputStream outToClient;
        private PrintWriter outToServer;
        private String currentVideo = null;
        private int lastDelivered = 0;

        // ⬇️ Added: Set to track which cached frames have been logged
        private final Set<String> cacheHitLogged = new HashSet<>();

        // ⬇️ Added: Set to track what frames have already been sent
        private final Set<Integer> deliveredFrames = new HashSet<>();

        public ClientHandler(Socket client, Socket server) {
            this.clientSocket = client;
            this.serverSocket = server;
        }

        public void run() {
            try {
                inFromServer = new DataInputStream(serverSocket.getInputStream());
                outToClient = new DataOutputStream(clientSocket.getOutputStream());
                outToServer = new PrintWriter(serverSocket.getOutputStream(), true);
                BufferedReader inFromClient = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

                while (true) {
                    String request = inFromClient.readLine();
                    if (request == null) break;

                    request = request.trim();
                    System.out.println("DEBUG (Relay): Raw received request: [" + request + "]");

                    if (request.equals("PING")) {
                        System.out.println("(Relay): Client is alive (PING).");
                        continue;
                    }
                    if (request.equals("DISCONNECT")) {
                        System.out.println("DEBUG (Relay): Client disconnected.");
                        clientSocket.close();
                        break;
                    }

                    handleFrameRequest(request);
                }
            } catch (IOException e) {
                System.out.println("DEBUG (Relay): Connection lost.");
            }
        }

        private void handleFrameRequest(String request) throws IOException {
            String[] parts = request.split(" ");
            if (parts.length != 2) {
                System.out.println("ERROR: Invalid request format!");
                return;
            }

            String videoName = parts[0];
            int totalRequestedFrames = Integer.parseInt(parts[1]);

            // Reset state when a new video is requested.
            if (currentVideo == null || !currentVideo.equals(videoName)) {
                currentVideo = videoName;
                lastDelivered = 0;
                cacheHitLogged.clear(); // Clear tracked logs on new video
                deliveredFrames.clear(); // Clear delivered frames on new video
            }

            // Ensure relay cache exists for this video.
            relayCache.putIfAbsent(videoName, new LinkedHashMap<Integer, byte[]>(BUFFER_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
                    return size() > BUFFER_SIZE; // Ensure the cache size doesn't exceed BUFFER_SIZE.
                }
            });

            LinkedHashMap<Integer, byte[]> videoCache = relayCache.get(videoName);
            List<Integer> missingFrames = new ArrayList<>();

            // Send frames from cache (from 0 to 99 or any existing frames in the cache).
            for (int frameIndex = 0; frameIndex < totalRequestedFrames; frameIndex++) {
                if (deliveredFrames.contains(frameIndex)) continue;

                if (videoCache.containsKey(frameIndex)) {
                    String key = videoName + "_" + frameIndex;
                    if (!cacheHitLogged.contains(key)) {
                        System.out.println("DEBUG (Relay): Sending " + videoName + " frame " + frameIndex + " from cache ✅");
                        cacheHitLogged.add(key);
                    }
                    byte[] cachedFrame = videoCache.get(frameIndex);
                    sendFrame(cachedFrame, calculateChecksum(cachedFrame), frameIndex, true);
                    deliveredFrames.add(frameIndex);
                } else {
                    missingFrames.add(frameIndex); // Collect frames that are not in cache.
                }
            }

            // For frames not in cache (e.g., from 100 to 149), request them from the server.
            for (int frameIndex : missingFrames) {
                System.out.println("DEBUG (Relay): Frame " + frameIndex + " not in cache. Requesting from server...");
                requestFrameFromServer(videoName, frameIndex);
            }

            lastDelivered = totalRequestedFrames; // Update last delivered frame.
        }

        private void requestFrameFromServer(String videoName, int frameIndex) throws IOException {
            long startTime = System.currentTimeMillis();
            outToServer.println(videoName + " " + frameIndex);

            int frameSize = inFromServer.readInt();
            byte[] frameData = new byte[frameSize];
            inFromServer.readFully(frameData);
            int checksum = inFromServer.readInt();
            long endTime = System.currentTimeMillis();

            long rtt = endTime - startTime;
            onSuccessfulTransmission(rtt);

            relayCache.get(videoName).put(frameIndex, frameData);
            sendFrame(frameData, checksum, frameIndex, false);
            deliveredFrames.add(frameIndex); // ✅ Mark as delivered
        }

        private void sendFrame(byte[] frame, int checksum, int frameIndex, boolean fromCache) throws IOException {
            if (fromCache) {
                System.out.println("DEBUG (Relay): Sending frame " + frameIndex + " from cache ✅");
            } else {
                System.out.println("DEBUG (Relay): Forwarding frame " + frameIndex + " from server 🔄");
            }
            outToClient.writeInt(frame.length);
            outToClient.write(frame);
            outToClient.writeInt(checksum);
            outToClient.flush();
        }

        private int calculateChecksum(byte[] data) {
            int checksum = 0;
            for (byte b : data)
                checksum += (b & 0xFF);
            return checksum;
        }
    }
}
