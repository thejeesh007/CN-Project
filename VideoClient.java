import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;

public class VideoClient {
    private static final int HEARTBEAT_INTERVAL = 5000;
    private static boolean isPaused = false;
    private static boolean isRunning = true;
    private static long lastFrameTime = System.currentTimeMillis();
    private static JLabel fpsLabel, logLabel;
    private static JProgressBar progressBar;
    private static JFrame frame;
    private static JPanel controlPanel;
    private static JLabel videoLabel;
    private static boolean darkMode = false;
    private static JSlider timelineSlider;
    private static Map<Integer, BufferedImage> frameCache = new HashMap<>();

    // TCP AIMD Congestion Control Variables
    private static int cwnd = 1;
    private static int ssthresh = 10;
    private static final int maxCwnd = 50;
    
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
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter relay IP Address: ");
        String relayIP = scanner.nextLine();
        System.out.print("Enter relay port: ");
        int relayPort = scanner.nextInt();
        scanner.nextLine();
        System.out.println("The device is running in IP Address: " + getWifiIPAddress());
        
        // Maintain a single connection to the relay throughout
        Socket relaySocket = new Socket(relayIP, relayPort);
        System.out.println("Client connected to relay on port " + relayPort);
        String IP = InetAddress.getLocalHost().getHostAddress();
        System.out.println("Client is running in IP: "+IP);
        DataInputStream in = new DataInputStream(relaySocket.getInputStream());
        PrintWriter out = new PrintWriter(relaySocket.getOutputStream(), true);

        // Heartbeat Mechanism
        new Thread(() -> {
            try {
                while (isRunning) {
                    out.println("PING");
                    Thread.sleep(HEARTBEAT_INTERVAL);
                }
            } catch (Exception e) {
                System.out.println("Heartbeat stopped.");
            }
        }).start();

        // UI Setup
        frame = new JFrame("Live Video Stream with ABR + TCP AIMD");
        frame.setLayout(new BorderLayout());
        frame.setSize(1000, 700);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);

        videoLabel = new JLabel();
        videoLabel.setHorizontalAlignment(JLabel.CENTER);
        videoLabel.setBorder(BorderFactory.createLineBorder(Color.BLACK, 2));
        frame.add(videoLabel, BorderLayout.CENTER);

        // Control Panel UI
        controlPanel = new JPanel();
        controlPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 15, 10));
        controlPanel.setBackground(new Color(230, 230, 230));

        JButton pauseButton = createStyledButton("⏸ Pause", new Color(255, 165, 0));
        JButton resumeButton = createStyledButton("▶ Resume", new Color(50, 205, 50));
        JButton stopButton = createStyledButton("⏹ Stop", new Color(255, 69, 0));
        JButton darkModeButton = createStyledButton("🌙 Dark Mode", new Color(100, 100, 100));

        fpsLabel = new JLabel("FPS: 0", JLabel.CENTER);
        fpsLabel.setFont(new Font("SansSerif", Font.BOLD, 14));

        logLabel = new JLabel("Logs: OK", JLabel.CENTER);
        logLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        logLabel.setForeground(Color.DARK_GRAY);

        progressBar = new JProgressBar(0, 100);
        progressBar.setPreferredSize(new Dimension(300, 20));
        progressBar.setStringPainted(true);

        // Timeline Slider
        timelineSlider = new JSlider(0, 100, 0);
        timelineSlider.setMajorTickSpacing(10);
        timelineSlider.setMinorTickSpacing(1);
        timelineSlider.setPaintTicks(true);
        timelineSlider.setPaintLabels(true);
        timelineSlider.setEnabled(false);
        frame.add(timelineSlider, BorderLayout.SOUTH);

        controlPanel.add(pauseButton);
        controlPanel.add(resumeButton);
        controlPanel.add(stopButton);
        controlPanel.add(darkModeButton);
        controlPanel.add(fpsLabel);

        frame.add(controlPanel, BorderLayout.NORTH);
        frame.add(logLabel, BorderLayout.SOUTH);
        frame.add(progressBar, BorderLayout.SOUTH);
        frame.setVisible(true);

        // Button Listeners
        pauseButton.addActionListener(e -> {
            isPaused = true;
            logLabel.setText("Logs: Paused");
        });

        resumeButton.addActionListener(e -> {
            isPaused = false;
            logLabel.setText("Logs: Resumed");
        });

        stopButton.addActionListener(e -> {
            isRunning = false;
            logLabel.setText("Logs: Stopped");
            try {
                relaySocket.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            frame.dispose();
        });

        darkModeButton.addActionListener(e -> toggleDarkMode());

        // Main loop for video requests
        while (isRunning) {
            System.out.print("Enter video name and number of frames (e.g., marc 300) or 0 to exit: ");
            String videoInput = scanner.nextLine();

            if (videoInput.equals("0")) {
                out.println("DISCONNECT");
                System.out.println("Client: Disconnecting...");
                relaySocket.close();
                break;
            }

            String[] inputParts = videoInput.split(" ");
            if (inputParts.length != 2) {
                System.out.println("Invalid input format! Use: <VideoName> <NumberOfFrames>");
                continue;
            }

            String videoName = inputParts[0];
            int numFrames = Integer.parseInt(inputParts[1]);

            progressBar.setMaximum(numFrames);
            progressBar.setValue(0);
            timelineSlider.setMaximum(numFrames);
            timelineSlider.setValue(0);
            timelineSlider.setEnabled(true);

            // Request each frame individually
            for (int frameIndex = 0; frameIndex < numFrames; frameIndex++) {
                while (isPaused) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {}
                }

                System.out.println("Client: Requesting frame " + frameIndex);
                // Send individual frame request
                out.println(videoName + " " + frameIndex);

                // Receive frame data
                int frameSize = in.readInt();
                byte[] frameData = new byte[frameSize];
                in.readFully(frameData);
                int receivedChecksum = in.readInt();
                
                // Receive cache status (new field added)
                boolean fromCache = in.readBoolean();
                String cacheStatus = fromCache ? "from CACHE" : "from SERVER";
                System.out.println("Client: Received frame " + frameIndex + " " + cacheStatus);

                // Verify checksum
                if (receivedChecksum != calculateChecksum(frameData)) {
                    logLabel.setText("Logs: Checksum failed for frame " + frameIndex + ". Requesting retransmission.");
                    out.println("NACK " + frameIndex);
                    ssthresh = Math.max(cwnd / 2, 1);
                    cwnd = 1;
                    frameIndex--; // Retry the same frame
                    continue;
                } else {
                    // AIMD congestion control
                    if (cwnd < ssthresh) {
                        cwnd *= 2; // Exponential increase
                    } else {
                        cwnd++; // Additive increase
                    }
                    cwnd = Math.min(cwnd, maxCwnd);
                }

                // Process the received frame
                ByteArrayInputStream bis = new ByteArrayInputStream(frameData);
                BufferedImage image = null;
                try {
                    image = ImageIO.read(bis);
                } catch (Exception e) {
                    System.out.println("Error decoding frame " + frameIndex + ": " + e.getMessage());
                    frameIndex--; // Retry the same frame
                    continue;
                }

                // Display the frame
                if (image != null) {
                    final BufferedImage displayImage = image;
                    final int currentFrame = frameIndex;
                    final String logMessage = "Logs: Displaying frame " + currentFrame + " " + cacheStatus;
                    
                    SwingUtilities.invokeLater(() -> {
                        videoLabel.setIcon(new ImageIcon(displayImage));
                        frame.repaint();
                        logLabel.setText(logMessage);
                        progressBar.setValue(currentFrame + 1);
                        timelineSlider.setValue(currentFrame);
                    });

                    // Cache management
                    if (frameCache.size() > 100) {
                        int firstKey = frameCache.keySet().iterator().next();
                        if (frameCache.get(firstKey) != null) {
                            frameCache.get(firstKey).flush();
                        }
                        frameCache.remove(firstKey);
                    }
                    frameCache.put(frameIndex, image);

                    // FPS calculation
                    long currentTime = System.currentTimeMillis();
                    double fps = 1000.0 / (currentTime - lastFrameTime);
                    lastFrameTime = currentTime;
                    final String fpsText = "FPS: " + String.format("%.2f", fps);
                    
                    SwingUtilities.invokeLater(() -> {
                        fpsLabel.setText(fpsText);
                    });
                }

                // Memory management
                if (frameIndex % 50 == 0) {
                    System.gc();
                    System.out.println("DEBUG: Memory cleaned at frame " + frameIndex);
                }
                
                System.out.println("Client: cwnd = " + cwnd + ", ssthresh = " + ssthresh);
            }
        }
    }

    private static JButton createStyledButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setBackground(color);
        button.setForeground(Color.WHITE);
        return button;
    }

    private static void toggleDarkMode() {
        darkMode = !darkMode;
        if (darkMode) {
            frame.getContentPane().setBackground(new Color(40, 40, 40));
            controlPanel.setBackground(new Color(60, 60, 60));
            videoLabel.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
            logLabel.setForeground(Color.LIGHT_GRAY);
        } else {
            frame.getContentPane().setBackground(UIManager.getColor("Panel.background"));
            controlPanel.setBackground(new Color(230, 230, 230));
            videoLabel.setBorder(BorderFactory.createLineBorder(Color.BLACK, 2));
            logLabel.setForeground(Color.DARK_GRAY);
        }
        frame.repaint();
    }

    private static int calculateChecksum(byte[] data) {
        int checksum = 0;
        for (byte b : data) checksum += (b & 0xFF);
        return checksum;
    }
}
