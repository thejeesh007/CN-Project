import java.io.*;
import java.net.*;
import java.util.*;

class VideoServer {
    private static final int SERVER_PORT = 5000;
    public static final String VIDEO_DIR = "Videos/";         
    public static final String FRAME_DIR = "Video Frames/";//path to store frame//
    public static final Map<String, File[]> videoFrames = new HashMap<>();
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
    public static void main(String[] args) throws IOException, InterruptedException {
        processVideos();
        
        System.out.println("Server is running in IP: "+getWifiIPAddress()+"at port no"+SERVER_PORT);
        ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
        System.out.println("DEBUG (Server): Waiting for relay connections...");

        while (true) {
            Socket relaySocket = serverSocket.accept();
            System.out.println("DEBUG (Server): Relay connected!");
            new Thread(new RelayHandler(relaySocket)).start();
        }
    }

    private static void processVideos() {
        File videoFolder = new File(VIDEO_DIR);
        if (!videoFolder.exists()) {
            System.out.println("ERROR: Videos folder not found!");
            return;
        }

        File[] videoFiles = videoFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".mp4") || name.toLowerCase().endsWith(".avi"));
        if (videoFiles == null || videoFiles.length == 0) {
            System.out.println("ERROR: No video files found in " + VIDEO_DIR);
            return;
        }

        for (File video : videoFiles) {
            String videoName = video.getName().replaceAll("\\..+$", "");
            String framePath = FRAME_DIR + videoName;
            File frameDir = new File(framePath);

            if (!frameDir.exists()) {
                frameDir.mkdirs();
                System.out.println("DEBUG (Server): Extracting frames for " + video.getName());
                extractFrames(video.getAbsolutePath(), framePath);
            } else {
                System.out.println("DEBUG (Server): Frames for " + video.getName() + " already exist. Skipping extraction.");
            }

            File[] frames = frameDir.listFiles((dir, name) -> name.endsWith(".jpg"));
            if (frames != null) {
                Arrays.sort(frames, Comparator.comparing(File::getName));
                videoFrames.put(videoName, frames);
                System.out.println("DEBUG (Server): Processed " + frames.length + " frames for " + videoName);
            }
        }
    }

    private static void extractFrames(String videoPath, String outputDir) {
        try {
            List<String> command = Arrays.asList("ffmpeg", "-i", videoPath, "-vf", "fps=30", "-q:v", "2", outputDir + "/frame_%04d.jpg");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("FFmpeg: " + line);
            }
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("Frame extraction completed for " + videoPath);
            } else {
                System.out.println("ERROR: Frame extraction failed for " + videoPath);
            }
        } catch (Exception e) {
            System.out.println("ERROR: Frame extraction failed for " + videoPath);
            e.printStackTrace();
        }
    }
}

class RelayHandler implements Runnable {
    private Socket relaySocket;
    private DataOutputStream outToRelay;
    private BufferedReader inFromRelay;
    private String currentVideo = null;
    private File[] currentFrames = null;

    public RelayHandler(Socket relaySocket) {
        this.relaySocket = relaySocket;
    }

    public void run() {
        try {
            outToRelay = new DataOutputStream(relaySocket.getOutputStream());
            inFromRelay = new BufferedReader(new InputStreamReader(relaySocket.getInputStream()));

            while (true) {
                String request = inFromRelay.readLine();
                if (request == null) break;

                System.out.println("DEBUG (Server): Received request = " + request);

                String[] parts = request.split(" ");
                if (parts.length != 2) {
                    System.out.println("ERROR: Invalid request format!");
                    continue;
                }

                String videoName = parts[0];
                int frameIndex = Integer.parseInt(parts[1]);

                if (!VideoServer.videoFrames.containsKey(videoName)) {
                    System.out.println("ERROR: Requested video not found!");
                    continue;
                }

                currentFrames = VideoServer.videoFrames.get(videoName);
                sendFrame(frameIndex, videoName);
            }
        } catch (IOException e) {
            System.out.println("DEBUG (Server): Relay connection closed or error occurred.");
        }
    }

    private void sendFrame(int frameIndex, String videoName) throws IOException {
        if (frameIndex < 0 || frameIndex >= currentFrames.length) {
            System.out.println("DEBUG (Server): Invalid frame request: " + frameIndex);
            return;
        }

        File frameFile = currentFrames[frameIndex];
        byte[] frameData = readFileToByteArray(frameFile);
        int checksum = calculateChecksum(frameData);

        System.out.println("DEBUG (Server): Sending " + videoName + " frame " + frameIndex);

        outToRelay.writeInt(frameData.length);
        outToRelay.write(frameData);
        outToRelay.writeInt(checksum);
        outToRelay.flush();
    }

    private byte[] readFileToByteArray(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        fis.read(data);
        fis.close();
        return data;
    }

    private int calculateChecksum(byte[] data) {
        int checksum = 0;
        for (byte b : data) checksum += (b & 0xFF);
        return checksum;
    }
}
