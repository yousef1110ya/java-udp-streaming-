package client;
/*
 * ClientHandler 
 * this class will be mainly responsable for : 
 * 1- getting the data from a folder ( will name it seq ) 
 * 2- send the data as a datagram ( might also have to parse the data for the image and send it over more that one packet )
 * ============================================
 * ============================================ 
 * DEV_NOTES : 
 * this would be the heart of the application , and the logic of sending will go in here or in some support classes . 
 *  
 */
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
public class ClientHandler {
	/*
	 * DECLERATIONS
	 */
	private static final int CHUNK_SIZE = 8 * 1024 ; // 8KB 
	private static final int MAX_PACKET_SIZE = 60000; // less than 60KB
	private static final int ACK_TIMEOUT_MS = 10000;
    private static final int RETRIES = 20;
	private final DatagramSocket socket;
	private final InetAddress target; 
	private final int PORT ;
	private int fileIndex = 0;
	private static ExecutorService pool;
	/*
	 * CONSTRUCTORS 
	 */
	public ClientHandler() throws Exception {
		this("localhost" , 5000);
	}
	public ClientHandler(String host , int PORT) throws Exception {
		this.socket = new DatagramSocket();
		this.socket.setSoTimeout(ACK_TIMEOUT_MS);
		this.target = InetAddress.getByName(host);
		this.PORT = PORT;
	}
	/*
	 * METHODES
	 */
    public void sendImagesInOrder(Path folder) throws Exception {
        List<Path> images = Files.list(folder)
                .filter(Files::isRegularFile)
                .sorted((a, b) -> {
                    try {
                        BasicFileAttributes aa = Files.readAttributes(a, BasicFileAttributes.class);
                        BasicFileAttributes bb = Files.readAttributes(b, BasicFileAttributes.class);
                        return aa.creationTime().compareTo(bb.creationTime());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

         long fileIdCounter = System.currentTimeMillis(); // simple fileId base

        for (Path img : images) {
        	fileIdCounter++;
        	final long finalFileIdCounter = fileIdCounter;
        	pool.submit(()->{
			try {
//				sendSingleFile(finalFileIdCounter , img);
				sendSingleFile_withack(finalFileIdCounter , img);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        	});
        	
        }
    }
    private void sendSingleFile_withack(long fileId, Path img) throws Exception {
        byte[] fileBytes = Files.readAllBytes(img);
        String filename = img.getFileName().toString();
        byte[] nameBytes = filename.getBytes("UTF-8");

        int totalChunks = (fileBytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        System.out.printf("Sending %s (id=%d) size=%d bytes in %d chunks%n", filename, fileId, fileBytes.length, totalChunks);

        for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            int offset = chunkIndex * CHUNK_SIZE;
            int len = Math.min(CHUNK_SIZE, fileBytes.length - offset);
            byte[] chunk = Arrays.copyOfRange(fileBytes, offset, offset + len);

            // build packet
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeLong(fileId);
            dos.writeInt(totalChunks);
            dos.writeInt(chunkIndex);
            dos.writeShort(nameBytes.length);
            dos.write(nameBytes);
            dos.write(chunk);
            dos.flush();

            byte[] packetBytes = baos.toByteArray();
            if (packetBytes.length > MAX_PACKET_SIZE) {
                throw new IllegalStateException("Constructed packet exceeds MAX_PACKET_SIZE; reduce CHUNK_SIZE");
            }

            boolean acknowledged = false;
            int attempt = 0;
            while (!acknowledged && attempt < RETRIES) {
                attempt++;
                DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length, target, PORT);
                socket.send(packet);

                try {
                    // wait for ACK
                    byte[] ackBuf = new byte[13]; // 8 + 4 + 1
                    DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length);
                    socket.receive(ackPacket);

                    DataInputStream dis = new DataInputStream(new ByteArrayInputStream(ackPacket.getData(), 0, ackPacket.getLength()));
                    long ackFileId = dis.readLong();
                    int ackChunkIdx = dis.readInt();
                    byte status = dis.readByte();

                    if (ackFileId == fileId && ackChunkIdx == chunkIndex && status == 0) {
                        acknowledged = true;
                        //System.out.printf("ACK received for file %d chunk %d%n", fileId, chunkIndex);
                    } else {
                        // wrong ack; continue retrying
                    }
                } catch (SocketTimeoutException ste) {
                    System.out.printf("Timeout waiting ACK for file %d chunk %d (attempt %d)%n", fileId, chunkIndex, attempt);
                }
            }

            if (!acknowledged) {
                throw new IOException("Failed to get ACK after retries for file " + fileId + " chunk " + chunkIndex);
            }
        }

        System.out.printf("Completed send for file id=%d name=%s%n", fileId, img.getFileName().toString());
    }
    private void sendSingleFile(long fileId, Path img) throws Exception {
        byte[] fileBytes = Files.readAllBytes(img);
        String filename = img.getFileName().toString();
        byte[] nameBytes = filename.getBytes("UTF-8");

        int totalChunks = (fileBytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        System.out.printf("Sending %s (id=%d) size=%d bytes in %d chunks%n", filename, fileId, fileBytes.length, totalChunks);

        for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            int offset = chunkIndex * CHUNK_SIZE;
            int len = Math.min(CHUNK_SIZE, fileBytes.length - offset);
            byte[] chunk = Arrays.copyOfRange(fileBytes, offset, offset + len);

            // build packet
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeLong(fileId);
            dos.writeInt(totalChunks);
            dos.writeInt(chunkIndex);
            dos.writeShort(nameBytes.length);
            dos.write(nameBytes);
            dos.write(chunk);
            dos.flush();

            byte[] packetBytes = baos.toByteArray();
            if (packetBytes.length > MAX_PACKET_SIZE) {
                throw new IllegalStateException("Constructed packet exceeds MAX_PACKET_SIZE; reduce CHUNK_SIZE");
            }
            // remove the acknoldgment needing .
            DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length, target, PORT);
            socket.send(packet);
           
        }

        System.out.printf("Completed send for file id=%d name=%s%n", fileId, img.getFileName().toString());
    }

    public void close() {
        socket.close();
    }	
	/*
	 * APP_START 
	 */
	public static void main(String[] args) throws Exception {
		pool = Executors.newFixedThreadPool(5);

        ClientHandler sender = new ClientHandler();
        try {
            sender.sendImagesInOrder(Path.of("images"));
        } finally {
            //sender.close();
            pool.shutdown();  // Mandatory
            try {
                pool.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException ignored) {}
            
            System.out.println("All tasks finalized.");
        }
    }
}
