package client;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;

import protocol.Protocol;

public class ClientHandler {

    static class FrameBuffer {
        int totalParts;
        byte[][] parts;
        int receivedParts = 0;
        long startTimeNanos; // Timestamp when first packet of this frame arrived

        FrameBuffer(int totalParts) {
            this.totalParts = totalParts;
            this.parts = new byte[totalParts][];
            this.startTimeNanos = System.nanoTime(); // Record when frame reception starts
        }

    }

    public static void main(String[] args) throws Exception {
        // Create and show video streaming widget
        VideoStreamWidget videoWidget = new VideoStreamWidget();

        DatagramChannel channel = DatagramChannel.open();
        channel.bind(new InetSocketAddress(9001));
        channel.configureBlocking(false);

        ByteBuffer buf = ByteBuffer.allocate(Protocol.PACKET_SIZE);

        Map<Integer, FrameBuffer> frames = new HashMap<>();

        // Timing statistics
        long overallStartTimeNanos = -1;
        int totalFramesReceived = 0;
        long totalReceiveTimeNanos = 0;
        long minFrameTimeNanos = Long.MAX_VALUE;
        long maxFrameTimeNanos = 0;

        System.out.println("Client started, waiting for frames on port 9001...");
        System.out.println("Client bound to: " + channel.getLocalAddress());

        int packetsReceived = 0;
        long lastPacketTime = System.currentTimeMillis();

        while (true) {
            buf.clear();
            SocketAddress sender = channel.receive(buf);

            // Check if data was actually received
            if (sender == null || buf.position() == 0) {
                // Check if we've been waiting too long without receiving anything
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastPacketTime > 5000 && packetsReceived == 0) {
                    System.out.println("Still waiting for packets... (no packets received yet)");
                    lastPacketTime = currentTime;
                }
                // No data available - use yield instead of sleep for lower latency
                Thread.yield();
                continue;
            }

            packetsReceived++;
            lastPacketTime = System.currentTimeMillis();

            if (packetsReceived == 1) {
                System.out.println("First packet received from: " + sender);
            }

            // Reduced logging frequency for performance
            if (packetsReceived % 500 == 0) {
                System.out.println("Received " + packetsReceived + " packets so far...");
            }

            buf.flip();

            // Verify we have enough data for the header (8 bytes)
            if (buf.remaining() < Protocol.HEADER_SIZE) {
                System.out.println("Warning: Received packet too small, skipping");
                continue;
            }

            int frameId = buf.getInt();
            int totalParts = buf.getShort() & 0xFFFF;
            int partNumber = buf.getShort() & 0xFFFF;

            byte[] payload = new byte[buf.remaining()];
            buf.get(payload);

            // Reduced logging for performance - only log first few packets
            if (packetsReceived <= 3) {
                System.out.println(String.format("First packets received: frameId=%d, part=%d/%d",
                        frameId, partNumber, totalParts));
            }

            boolean isNewFrame = !frames.containsKey(frameId);
            frames.putIfAbsent(frameId, new FrameBuffer(totalParts));
            FrameBuffer fb = frames.get(frameId);

            // Record overall start time when first frame starts
            if (overallStartTimeNanos == -1 && isNewFrame) {
                overallStartTimeNanos = System.nanoTime();
            }

            if (fb.parts[partNumber] == null) {
                fb.parts[partNumber] = payload;
                fb.receivedParts++;
            }

            if (fb.receivedParts == totalParts) {
                // Calculate precise timing for this frame
                long frameEndTimeNanos = System.nanoTime();
                long frameDurationNanos = frameEndTimeNanos - fb.startTimeNanos;
                double frameDurationMs = frameDurationNanos / 1_000_000.0;

                // Update statistics
                totalFramesReceived++;
                totalReceiveTimeNanos += frameDurationNanos;
                minFrameTimeNanos = Math.min(minFrameTimeNanos, frameDurationNanos);
                maxFrameTimeNanos = Math.max(maxFrameTimeNanos, frameDurationNanos);

                // Calculate overall elapsed time
                long overallElapsedNanos = frameEndTimeNanos - overallStartTimeNanos;
                double overallElapsedMs = overallElapsedNanos / 1_000_000.0;
                double avgFrameTimeMs = (totalReceiveTimeNanos / (double) totalFramesReceived) / 1_000_000.0;

                // Reduced logging frequency for performance
                if (totalFramesReceived % 10 == 0 || totalFramesReceived <= 5) {
                    System.out.println(String.format(
                            "Frame %d received - Frame time: %.3f ms | Overall: %.3f ms | Avg: %.3f ms",
                            frameId, frameDurationMs, overallElapsedMs, avgFrameTimeMs));
                }

                byte[] fullImage = assembleFrame(fb);

                // Display frame in video widget (non-blocking)
                try {
                    videoWidget.updateFrame(fullImage);
                } catch (Exception e) {
                    System.err.println("Error updating video widget with frame " + frameId + ": " + e.getMessage());
                }

                // Save frame asynchronously to avoid blocking
                final int saveFrameId = frameId;
                final byte[] saveImage = fullImage;
                new Thread(() -> {
                    try {
                        saveFrame(saveFrameId, saveImage);
                    } catch (IOException e) {
                        System.err.println("Error saving frame " + saveFrameId + ": " + e.getMessage());
                    }
                }).start();

                frames.remove(frameId);

                // Print summary statistics periodically
                if (totalFramesReceived % 10 == 0) {
                    double minMs = minFrameTimeNanos / 1_000_000.0;
                    double maxMs = maxFrameTimeNanos / 1_000_000.0;
                    System.out.println(String.format(
                            "=== Statistics (after %d frames) === Min: %.3f ms | Max: %.3f ms | Avg: %.3f ms ===",
                            totalFramesReceived, minMs, maxMs, avgFrameTimeMs));
                }
            }

        }
    }

    public static byte[] assembleFrame(FrameBuffer fb) {
        int totalSize = 0;
        for (byte[] part : fb.parts) {
            totalSize += part.length;
        }

        byte[] fullImage = new byte[totalSize];
        int offset = 0;

        for (byte[] part : fb.parts) {
            System.arraycopy(part, 0, fullImage, offset, part.length);
            offset += part.length;
        }
        return fullImage;
    }

    public static void saveFrame(int frameId, byte[] fullImage) throws IOException {
        // Ensure saved_frames directory exists
        File savedFramesDir = new File("saved_frames");
        if (!savedFramesDir.exists()) {
            savedFramesDir.mkdirs();
        }

        FileOutputStream fos = new FileOutputStream("saved_frames/frame_" + frameId + ".jpg");
        fos.write(fullImage);
        fos.close();
        System.out.println("Saved frame_" + frameId + ".jpg to saved_frames folder");
    }

    /**
     * Video streaming widget that displays incoming frames in real-time
     */
    static class VideoStreamWidget {
        private volatile JFrame frame;
        private volatile JLabel imageLabel;
        private int displayWidth = 800;
        private int displayHeight = 600;
        private final Object initLock = new Object();
        private volatile boolean initialized = false;
        private volatile byte[] latestFrame = null;
        private volatile boolean isUpdating = false;

        public VideoStreamWidget() {
            // Create GUI on Event Dispatch Thread
            SwingUtilities.invokeLater(() -> {
                synchronized (initLock) {
                    frame = new JFrame("UDP Video Stream");
                    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    frame.setLayout(new BorderLayout());

                    imageLabel = new JLabel();
                    imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    imageLabel.setVerticalAlignment(SwingConstants.CENTER);
                    imageLabel.setBackground(Color.BLACK);
                    imageLabel.setOpaque(true);

                    JScrollPane scrollPane = new JScrollPane(imageLabel);
                    scrollPane.setBorder(null);

                    frame.add(scrollPane, BorderLayout.CENTER);

                    // Set initial size
                    frame.setSize(displayWidth, displayHeight);
                    frame.setLocationRelativeTo(null);
                    frame.setVisible(true);

                    // Show initial message
                    imageLabel.setText("<html><div style='text-align: center; color: white;'>" +
                            "Waiting for video stream...<br>" +
                            "Listening on port 9001</div></html>");

                    initialized = true;
                    initLock.notifyAll();
                }
            });

            // Wait a bit for GUI to initialize (but don't block forever)
            try {
                synchronized (initLock) {
                    if (!initialized) {
                        initLock.wait(2000); // Wait up to 2 seconds
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Update the display with a new frame (non-blocking, drops old frames if GUI is
         * busy)
         * 
         * @param imageBytes The JPEG image bytes
         */
        public void updateFrame(byte[] imageBytes) {
            if (imageBytes == null || imageBytes.length == 0) {
                return;
            }

            // Wait for GUI to be initialized
            if (!initialized || frame == null || imageLabel == null) {
                try {
                    synchronized (initLock) {
                        if (!initialized) {
                            initLock.wait(1000);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                if (frame == null || imageLabel == null) {
                    return;
                }
            }

            // Store latest frame (drop old ones if GUI is busy)
            latestFrame = imageBytes;

            // Only queue update if not already updating (prevents backlog)
            if (!isUpdating) {
                isUpdating = true;
                SwingUtilities.invokeLater(() -> {
                    byte[] frameToDisplay = latestFrame;
                    if (frameToDisplay == null) {
                        isUpdating = false;
                        return;
                    }

                    try {
                        // Convert byte array to BufferedImage
                        ByteArrayInputStream bais = new ByteArrayInputStream(frameToDisplay);
                        BufferedImage image = ImageIO.read(bais);
                        bais.close();

                        if (image != null) {
                            // Scale image to fit display while maintaining aspect ratio
                            ImageIcon icon = scaleImage(image, displayWidth, displayHeight);
                            imageLabel.setIcon(icon);
                            imageLabel.setText(null);

                            // Auto-resize window to fit image on first frame
                            if (frame.getWidth() == displayWidth && frame.getHeight() == displayHeight) {
                                Dimension preferredSize = icon.getIconWidth() > 0 && icon.getIconHeight() > 0
                                        ? new Dimension(Math.min(icon.getIconWidth() + 20, 1200),
                                                Math.min(icon.getIconHeight() + 60, 900))
                                        : new Dimension(displayWidth, displayHeight);
                                frame.setSize(preferredSize);
                                frame.setLocationRelativeTo(null);
                            }

                            // Only repaint if needed (revalidate is expensive)
                            imageLabel.repaint();
                        }
                    } catch (Exception e) {
                        // Silent failure to avoid spam
                    } finally {
                        isUpdating = false;
                        // Check if there's a newer frame waiting (compare by reference)
                        byte[] newerFrame = latestFrame;
                        if (newerFrame != null && newerFrame != frameToDisplay && !isUpdating) {
                            // Schedule update for the newer frame (non-recursive)
                            isUpdating = true;
                            SwingUtilities.invokeLater(() -> {
                                try {
                                    ByteArrayInputStream bais = new ByteArrayInputStream(newerFrame);
                                    BufferedImage img = ImageIO.read(bais);
                                    bais.close();
                                    if (img != null) {
                                        ImageIcon ic = scaleImage(img, displayWidth, displayHeight);
                                        imageLabel.setIcon(ic);
                                        imageLabel.setText(null);
                                        imageLabel.repaint();
                                    }
                                } catch (Exception e) {
                                    // Silent
                                } finally {
                                    isUpdating = false;
                                }
                            });
                        }
                    }
                });
            }
        }

        /**
         * Scale image to fit within max dimensions while maintaining aspect ratio
         */
        private ImageIcon scaleImage(BufferedImage image, int maxWidth, int maxHeight) {
            int originalWidth = image.getWidth();
            int originalHeight = image.getHeight();

            // Calculate scaling factor
            double widthScale = (double) maxWidth / originalWidth;
            double heightScale = (double) maxHeight / originalHeight;
            double scale = Math.min(widthScale, heightScale);

            // Only scale down, never scale up
            if (scale >= 1.0) {
                return new ImageIcon(image);
            }

            int newWidth = (int) (originalWidth * scale);
            int newHeight = (int) (originalHeight * scale);

            // Use faster scaling algorithm for real-time streaming
            Image scaledImage = image.getScaledInstance(newWidth, newHeight, Image.SCALE_FAST);
            BufferedImage bufferedScaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = bufferedScaled.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2d.drawImage(scaledImage, 0, 0, null);
            g2d.dispose();

            return new ImageIcon(bufferedScaled);
        }
    }
}
