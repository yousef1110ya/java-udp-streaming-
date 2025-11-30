package server;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

import protocol.Protocol;

public class ServerHandler {
    public static void main(String[] args) throws Exception {
        DatagramChannel channel = DatagramChannel.open();
        channel.configureBlocking(false);
        // Bind to a local port (optional but recommended for sending)
        channel.bind(null);

        InetSocketAddress address = new InetSocketAddress("localhost", 9001);
        System.out.println("Server will send to: " + address);

        // Use relative path from the project root
        File folder = new File("images");

        if (!folder.exists()) {
            System.out.println("Images folder does not exist at: " + folder.getAbsolutePath());
            return;
        }

        if (!folder.isDirectory()) {
            System.out.println("Images path is not a directory: " + folder.getAbsolutePath());
            return;
        }

        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg") ||
                name.toLowerCase().endsWith(".jpeg") ||
                name.toLowerCase().endsWith(".png"));

        if (files == null || files.length == 0) {
            System.out.println("No image files found in the images folder at: " + folder.getAbsolutePath());
            return;
        }

        System.out.println("Found " + files.length + " image files in: " + folder.getAbsolutePath());
        System.out.println("Waiting 1 second for client to be ready...");
        Thread.sleep(1000);
        System.out.println("Starting to send frames at maximum speed...");

        int frameId = 0;

        for (File file : files) {
            byte[] imageBytes = Files.readAllBytes(file.toPath());

            List<ByteBuffer> packets = splitFrame(imageBytes, frameId);

            System.out.println("Sending frame " + frameId + " with " + packets.size() + " packets");

            int packetsSent = 0;
            // Send all packets as fast as possible
            for (ByteBuffer packet : packets) {
                // Retry if send buffer is full (non-blocking mode)
                int bytesSent = 0;
                while (bytesSent == 0) {
                    bytesSent = channel.send(packet, address);
                    if (bytesSent == 0) {
                        // Buffer full, try again immediately (very rare on localhost)
                        Thread.yield(); // Give other threads a chance, but don't sleep
                        packet.rewind(); // Reset position for retry
                    }
                }
                packetsSent++;
            }

            if (frameId % 10 == 0) {
                System.out.println("Sent frame " + frameId + " with " + packetsSent + " packets");
            }

            frameId++;
            // Small delay between frames to allow client to process (prevents freezing)
            Thread.sleep(2);
        }
        System.out.println("All frames sent");
    }

    public static List<ByteBuffer> splitFrame(byte[] data, int frameId) {
        int totalParts = (int) Math.ceil(data.length / (double) Protocol.PAYLOAD_SIZE);

        List<ByteBuffer> list = new ArrayList<>(totalParts);

        for (int part = 0; part < totalParts; part++) {
            int start = part * Protocol.PAYLOAD_SIZE;
            int end = Math.min(start + Protocol.PAYLOAD_SIZE, data.length);
            int length = end - start;

            ByteBuffer buf = ByteBuffer.allocate(Protocol.HEADER_SIZE + length);

            buf.putInt(frameId); // 4 bytes
            buf.putShort((short) totalParts); // 2 bytes (was putInt - bug fix)
            buf.putShort((short) part); // 2 bytes

            buf.put(data, start, length);
            buf.flip();

            list.add(buf);
        }
        return list;
    }
}
