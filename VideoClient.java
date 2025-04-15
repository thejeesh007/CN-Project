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

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Enter relay port: ");
        int relayPort = scanner.nextInt();
        scanner.nextLine();

        Socket relaySocket = new Socket("localhost", relayPort);
        System.out.println("Client connected to relay on port " + relayPort);

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

        while (isRunning) {
            System.out.print("Enter video name and number of frames (e.g., marc 300) or 0 to exit: ");
            String videoInput = scanner.nextLine();

            if (videoInput.equals("0")) {
                out.println("DISCONNECT");
                System.out.println("Client: Disconnecting...");
                relaySocket.close();
                break;
            }

            out.println(videoInput);
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
            timelineSlider.setEnabled(true);

            for (int i = 0; i < numFrames; i += cwnd) {
                for (int j = i; j < Math.min(i + cwnd, numFrames); j++) {
                    while (isPaused) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ex) {}
                    }

                    System.out.println("Client: Requesting frame " + j);
                    out.println(videoName + " " + j);

                    int frameSize = in.readInt();
                    byte[] frameData = new byte[frameSize];
                    in.readFully(frameData);
                    int receivedChecksum = in.readInt();

                    if (receivedChecksum != calculateChecksum(frameData)) {
                        logLabel.setText("Logs: Checksum failed for frame " + j + ". Requesting retransmission.");
                        out.println("NACK " + j);
                        ssthresh = Math.max(cwnd / 2, 1);
                        cwnd = 1;
                    } else {
                        if (cwnd < ssthresh) {
                            cwnd *= 2;
                        } else {
                            cwnd++;
                        }
                        cwnd = Math.min(cwnd, maxCwnd);
                    }

                    ByteArrayInputStream bis = new ByteArrayInputStream(frameData);
                    BufferedImage image = null;
                    try {
                        image = ImageIO.read(bis);
                    } catch (Exception e) {
                        System.out.println("Error decoding frame " + j + ": " + e.getMessage());
                    }

                    if (image != null) {
                        videoLabel.setIcon(new ImageIcon(image));
                        frame.repaint();
                        logLabel.setText("Logs: Displaying frame " + j);
                        progressBar.setValue(j + 1);
                        timelineSlider.setValue(j);

                        // ✅ Free old cached frames
                        if (frameCache.size() > 100) {
                            int firstKey = frameCache.keySet().iterator().next();
                            frameCache.get(firstKey).flush();
                            frameCache.remove(firstKey);
                        }

                        frameCache.put(j, image);

                        long currentTime = System.currentTimeMillis();
                        double fps = 1000.0 / (currentTime - lastFrameTime);
                        lastFrameTime = currentTime;
                        fpsLabel.setText("FPS: " + String.format("%.2f", fps));

                        image.flush();
                        image = null;
                    }
                }
                // ✅ Force garbage collection every 50 frames
                if (i % 50 == 0) {
                    System.gc();
                    System.out.println("DEBUG: Memory cleaned at frame " + i);
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
    }

    private static int calculateChecksum(byte[] data) {
        int checksum = 0;
        for (byte b : data) checksum += (b & 0xFF);
        return checksum;
    }
}
