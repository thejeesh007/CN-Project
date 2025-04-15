import java.io.*;
import java.net.*;
import java.util.*;

class VideoRelay {
    private static final int SERVER_PORT = 5000;
    private static final int BUFFER_SIZE = 500; // Increased from 100 to 500
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
    // Making the cache size much larger to preserve more frames across requests
    public static final Map<String, Map<Integer, byte[]>> relayCache = new HashMap<>();
    
    private static String getWifiIPAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface net = interfaces.nextElement();
                if (net.isUp() && !net.isLoopback() && !net.getDisplayName().toLowerCase().contains("virtual")) {
                    Enumeration<InetAddress> addresses = net.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && addr.getHostAddress().startsWith("192.168")) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "localhost";
    }
    
    public static void main(String[] args) throws IOException {
        Scanner sc = new Scanner(System.in);
        System.out.println("Enter Server IP Address");
        String serverIP = sc.nextLine();
        Socket serverSocket = new Socket(serverIP, SERVER_PORT); // Linux-compatible IP
        System.out.println("DEBUG (Relay): Connected to server at port " + SERVER_PORT);
        System.out.println("Relay is running in IP: "+ getWifiIPAddress());
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
        
        // Stats tracking
        private int cacheHits = 0;
        private int cacheMisses = 0;
        private Map<String, Integer> videoHitStats = new HashMap<>();
        private Map<String, Integer> videoMissStats = new HashMap<>();

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

                    // Handle individual frame request (from client incremental fetch)
                    if (request.startsWith("NACK")) {
                        String[] nackParts = request.split(" ");
                        if (nackParts.length == 2) {
                            int frameIndex = Integer.parseInt(nackParts[1]);
                            // This would need additional handling for NACK
                            continue;
                        }
                    }

                    // Handle normal video request which comes directly from client
                    handleVideoRequest(request);
                    
                    // Print cache stats periodically
                    if ((cacheHits + cacheMisses) % 50 == 0) {
                        printCacheStats();
                    }
                }
            } catch (IOException e) {
                System.out.println("DEBUG (Relay): Connection lost.");
                e.printStackTrace();
            }
        }

        private void printCacheStats() {
            System.out.println("========= CACHE STATISTICS =========");
            System.out.println("Total cache hits: " + cacheHits);
            System.out.println("Total cache misses: " + cacheMisses);
            double hitRate = (double) cacheHits / (cacheHits + cacheMisses) * 100;
            System.out.println("Cache hit rate: " + String.format("%.2f", hitRate) + "%");
            
            System.out.println("----- Per Video Statistics -----");
            for (String video : videoHitStats.keySet()) {
                int hits = videoHitStats.getOrDefault(video, 0);
                int misses = videoMissStats.getOrDefault(video, 0);
                double rate = (double) hits / (hits + misses) * 100;
                System.out.println(video + ": " + hits + " hits, " + misses + " misses (" + 
                                  String.format("%.2f", rate) + "% hit rate)");
            }
            
            // Add cache size information
            System.out.println("----- Cache Size Information -----");
            for (String video : relayCache.keySet()) {
                System.out.println(video + ": " + relayCache.get(video).size() + " frames in cache");
            }
            
            System.out.println("===================================");
        }

       private void handleVideoRequest(String request) throws IOException {
            String[] parts = request.split(" ");
            if (parts.length != 2) {
                System.out.println("ERROR: Invalid request format!");
                return;
            }

            String videoName = parts[0];
            int frameIndex = Integer.parseInt(parts[1]);

            // Ensure cache exists for this video
            if (!relayCache.containsKey(videoName)) {
                // Create a cache with a higher capacity and true access-order parameter
                relayCache.put(videoName, Collections.synchronizedMap(new LinkedHashMap<Integer, byte[]>(BUFFER_SIZE, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
                        return size() > BUFFER_SIZE;
                    }
                }));
                System.out.println("DEBUG (Relay): Created new cache for " + videoName);
            }

            Map<Integer, byte[]> videoCache = relayCache.get(videoName);

            // Debug: show current cache keys
            System.out.println("DEBUG (Relay): Cache keys for " + videoName + " = " + videoCache.keySet());

            // Check if frame is in cache
            if (videoCache.containsKey(frameIndex)) {
                System.out.println("DEBUG (Relay): Cache HIT for " + videoName + " frame " + frameIndex + " ✅");
                byte[] cachedFrame = videoCache.get(frameIndex);
                
                // Update cache statistics
                cacheHits++;
                videoHitStats.put(videoName, videoHitStats.getOrDefault(videoName, 0) + 1);
                
                // Move this entry to the end of the LRU queue by accessing it
                videoCache.get(frameIndex);
                
                sendFrame(cachedFrame, calculateChecksum(cachedFrame), frameIndex, true);
            } else {
                System.out.println("DEBUG (Relay): Cache MISS for " + videoName + " frame " + frameIndex + ". Requesting from server...");
                
                // Update cache statistics
                cacheMisses++;
                videoMissStats.put(videoName, videoMissStats.getOrDefault(videoName, 0) + 1);
                
                requestFrameFromServer(videoName, frameIndex);
            }
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

            // Store in cache
            Map<Integer, byte[]> videoCache = relayCache.get(videoName);
            videoCache.put(frameIndex, frameData);

            System.out.println("DEBUG (Relay): Stored frame " + frameIndex + " into cache for " + videoName);
            System.out.println("DEBUG (Relay): Cache size for " + videoName + " = " + videoCache.size());

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
            outToClient.writeBoolean(fromCache); // Send cache status to client
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
