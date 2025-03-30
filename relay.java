import java.io.*;
import java.net.*;
import java.util.*;

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
        Socket serverSocket = new Socket("localhost", SERVER_PORT);
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
        // currentVideo holds the video name for the current session
        private String currentVideo = null;
        // lastDelivered tracks the number of frames already sent to this client for the current video.
        private int lastDelivered = 0;

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

                    // Handle heartbeat
                    if (request.equals("PING")) {
                        System.out.println("(Relay): Client is alive (PING).");
                        continue;
                    }
                    // Handle disconnect
                    if (request.equals("DISCONNECT")) {
                        System.out.println("DEBUG (Relay): Client disconnected.");
                        clientSocket.close();
                        break;
                    }
                    
                    // Now, expect requests in the format: "<VideoName> <TotalFramesRequested>"
                    handleFrameRequest(request);
                }
            } catch (IOException e) {
                System.out.println("DEBUG (Relay): Connection lost.");
            }
        }

        // This method processes a request of the form: "videoName totalRequestedFrames"
        private void handleFrameRequest(String request) throws IOException {
            String[] parts = request.split(" ");
            if (parts.length != 2) {
                System.out.println("ERROR: Invalid request format!");
                return;
            }

            String videoName = parts[0];
            int totalRequestedFrames = Integer.parseInt(parts[1]);

            // If a new video is requested, update currentVideo and reset lastDelivered
            if (currentVideo == null || !currentVideo.equals(videoName)) {
                currentVideo = videoName;
                lastDelivered = 0;
            }
            
            // Initialize cache for this video if not present
            relayCache.putIfAbsent(videoName, new LinkedHashMap<Integer, byte[]>(BUFFER_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
                    return size() > BUFFER_SIZE;
                }
            });

            LinkedHashMap<Integer, byte[]> videoCache = relayCache.get(videoName);
            List<Integer> missingFrames = new ArrayList<>();

            // Only send frames that have not yet been delivered
            for (int frameIndex = lastDelivered; frameIndex < totalRequestedFrames; frameIndex++) {
                if (videoCache.containsKey(frameIndex)) {
                    System.out.println("DEBUG (Relay): Sending " + videoName + " frame " + frameIndex + " from cache ✅");
                    byte[] cachedFrame = videoCache.get(frameIndex);
                    sendFrame(cachedFrame, calculateChecksum(cachedFrame), frameIndex, true);
                } else {
                    missingFrames.add(frameIndex);
                }
            }

            // Request and send only the missing frames
            for (int frameIndex : missingFrames) {
                System.out.println("DEBUG (Relay): Frame " + frameIndex + " not in cache. Requesting from server...");
                requestFrameFromServer(videoName, frameIndex);
            }
            // Update lastDelivered so that next time we only send new frames.
            lastDelivered = totalRequestedFrames;
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

            // Store the newly fetched frame in cache
            relayCache.get(videoName).put(frameIndex, frameData);
            sendFrame(frameData, checksum, frameIndex, false);
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
