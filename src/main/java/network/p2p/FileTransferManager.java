package network.p2p;

import protocol.P2PMessageProtocol;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * FileTransferManager - Quản lý việc gửi và nhận file qua P2P
 */
public class FileTransferManager {
    private static final int CHUNK_SIZE = 64 * 1024; // 64KB per chunk
    private static final String DOWNLOAD_DIR = "downloads";
    
    // Map: fileId -> FileTransferSession
    private final Map<String, FileTransferSession> activeSessions = new ConcurrentHashMap<>();
    
    // Callbacks
    private FileTransferListener listener;
    
    public interface FileTransferListener {
        void onFileRequest(Integer fromUser, String fileId, String fileName, Long fileSize);
        void onFileAccepted(String fileId);
        void onFileRejected(String fileId, String reason);
        void onFileProgress(String fileId, int progress); // 0-100
        void onFileCompleted(String fileId, Path savedPath);
        void onFileCancelled(String fileId, String reason);
        void onFileError(String fileId, String error);
    }
    
    public FileTransferManager() {
        // Create download directory
        try {
            Files.createDirectories(Paths.get(DOWNLOAD_DIR));
        } catch (IOException e) {
            System.err.println("Failed to create download directory: " + e.getMessage());
        }
    }
    
    // ===== SENDING FILE =====
    
    /**
     * Gửi file request tới peer
     */
    public String requestFileSend(File file, Integer toUserId, Integer conversationId, 
                                  P2PManager p2pManager) {
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a file");
        }
        
        String fileId = UUID.randomUUID().toString();
        FileTransferSession session = new FileTransferSession(
            fileId, file, toUserId, conversationId, true
        );
        activeSessions.put(fileId, session);
        
     // Gọi P2PManager để gửi request (P2PManager sẽ set 'from' = localUserId)
        boolean sent = p2pManager.sendFileRequest(
            toUserId,
            conversationId,
            file.getName(),
            file.length(),
            fileId
        );

        if (sent) {
            System.out.println("📤 Sent file request: " + file.getName());
        } else {
            System.err.println("❌ Failed to send file request: " + file.getName());
        }

        return fileId;

    }
    
    /**
     * Bắt đầu gửi file sau khi được accept
     */
    public void startFileSend(String fileId, P2PManager p2pManager) {
        FileTransferSession session = activeSessions.get(fileId);
        if (session == null || !session.isSending) {
            System.err.println("Invalid file session: " + fileId);
            return;
        }
        
        new Thread(() -> {
            try {
                sendFileChunks(session, p2pManager);
            } catch (Exception e) {
                System.err.println("Error sending file: " + e.getMessage());
                if (listener != null) {
                    listener.onFileError(fileId, e.getMessage());
                }
            } finally {
                activeSessions.remove(fileId);
            }
        }, "file-sender-" + fileId).start();
    }
    
    private void sendFileChunks(FileTransferSession session, P2PManager p2pManager) throws IOException {
        File file = session.file;
        int totalChunks = (int) Math.ceil((double) file.length() / CHUNK_SIZE);
        
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            
            byte[] buffer = new byte[CHUNK_SIZE];
            int chunkIndex = 0;
            int bytesRead;
            
            while ((bytesRead = bis.read(buffer)) > 0) {
                // Prepare chunk data
                byte[] chunkData = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                
                // Send chunk
                String json = P2PMessageProtocol.buildFileChunk(
                    null, // Will be set by P2PManager
                    session.peerId,
                    session.fileId,
                    chunkIndex,
                    totalChunks,
                    chunkData
                );
                
                PeerConnection conn = p2pManager.getOrCreateConnection(session.peerId);
                if (conn != null) {
                    conn.sendTcp(json);
                }
                
                // Update progress
                int progress = (int) (((chunkIndex + 1) * 100.0) / totalChunks);
                if (listener != null) {
                    listener.onFileProgress(session.fileId, progress);
                }
                
                chunkIndex++;
                Thread.sleep(10); // Small delay to avoid overwhelming
            }
            
            // Send complete signal
            String completeJson = P2PMessageProtocol.buildFileComplete(
                null,
                session.peerId,
                session.fileId
            );
            
            PeerConnection conn = p2pManager.getOrCreateConnection(session.peerId);
            if (conn != null) {
                conn.sendTcp(completeJson);
            }
            
            System.out.println("✅ File sent successfully: " + file.getName());
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // ===== RECEIVING FILE =====
    
    /**
     * Xử lý file request từ peer
     */
    public void handleFileRequest(P2PMessageProtocol.Message msg) {
        String fileId = (String) msg.data.get("fileId");
        String fileName = (String) msg.data.get("fileName");
        Number fileSize = (Number) msg.data.get("fileSize");
        
        FileTransferSession session = new FileTransferSession(
            fileId, fileName, fileSize.longValue(), msg.from, msg.conversationId, false
        );
        activeSessions.put(fileId, session);
        
        if (listener != null) {
            listener.onFileRequest(msg.from, fileId, fileName, fileSize.longValue());
        }
    }
    
    /**
     * Accept file transfer
     */
    public void acceptFile(String fileId, P2PManager p2pManager) {
        FileTransferSession session = activeSessions.get(fileId);
        if (session == null) {
            System.err.println("File session not found: " + fileId);
            return;
        }
        
        String json = P2PMessageProtocol.buildFileAccept(null, session.peerId, fileId);
        PeerConnection conn = p2pManager.getOrCreateConnection(session.peerId);
        if (conn != null) {
            conn.sendTcp(json);
        }
        
        // Prepare to receive
        session.receivedChunks = new ConcurrentHashMap<>();
    }
    
    /**
     * Reject file transfer
     */
    public void rejectFile(String fileId, String reason, P2PManager p2pManager) {
        FileTransferSession session = activeSessions.get(fileId);
        if (session == null) return;
        
        String json = P2PMessageProtocol.buildFileReject(null, session.peerId, fileId, reason);
        PeerConnection conn = p2pManager.getOrCreateConnection(session.peerId);
        if (conn != null) {
            conn.sendTcp(json);
        }
        
        activeSessions.remove(fileId);
    }
    
    /**
     * Xử lý file chunk nhận được
     */
    public void handleFileChunk(P2PMessageProtocol.Message msg) {
        String fileId = (String) msg.data.get("fileId");
        Number chunkIndex = (Number) msg.data.get("chunkIndex");
        Number totalChunks = (Number) msg.data.get("totalChunks");
        String chunkDataBase64 = (String) msg.data.get("chunkData");
        
        FileTransferSession session = activeSessions.get(fileId);
        if (session == null || session.isSending) {
            System.err.println("Invalid file session for chunk: " + fileId);
            return;
        }
        
        // Decode chunk data
        byte[] chunkData = java.util.Base64.getDecoder().decode(chunkDataBase64);
        session.receivedChunks.put(chunkIndex.intValue(), chunkData);
        
        // Update progress
        int progress = (int) ((session.receivedChunks.size() * 100.0) / totalChunks.intValue());
        if (listener != null) {
            listener.onFileProgress(fileId, progress);
        }
    }
    
    /**
     * Xử lý file complete signal
     */
    public void handleFileComplete(P2PMessageProtocol.Message msg) {
        String fileId = (String) msg.data.get("fileId");
        FileTransferSession session = activeSessions.get(fileId);
        
        if (session == null || session.isSending) {
            return;
        }
        
        try {
            // Save file
            Path savePath = Paths.get(DOWNLOAD_DIR, session.fileName);
            
            try (FileOutputStream fos = new FileOutputStream(savePath.toFile());
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                
                // Write chunks in order
                for (int i = 0; i < session.receivedChunks.size(); i++) {
                    byte[] chunk = session.receivedChunks.get(i);
                    if (chunk != null) {
                        bos.write(chunk);
                    }
                }
            }
            
            System.out.println("✅ File received: " + savePath);
            
            if (listener != null) {
                listener.onFileCompleted(fileId, savePath);
            }
            
        } catch (IOException e) {
            System.err.println("Error saving file: " + e.getMessage());
            if (listener != null) {
                listener.onFileError(fileId, e.getMessage());
            }
        } finally {
            activeSessions.remove(fileId);
        }
    }
    
    /**
     * Handle file accepted response
     */
    public void handleFileAccept(P2PMessageProtocol.Message msg, P2PManager p2pManager) {
        String fileId = (String) msg.data.get("fileId");
        
        if (listener != null) {
            listener.onFileAccepted(fileId);
        }
        
        // Start sending
        startFileSend(fileId, p2pManager);
    }
    
    /**
     * Handle file rejected response
     */
    public void handleFileReject(P2PMessageProtocol.Message msg) {
        String fileId = (String) msg.data.get("fileId");
        String reason = (String) msg.data.get("reason");
        
        if (listener != null) {
            listener.onFileRejected(fileId, reason);
        }
        
        activeSessions.remove(fileId);
    }
    
    /**
     * Cancel file transfer
     */
    public void cancelTransfer(String fileId, String reason, P2PManager p2pManager) {
        FileTransferSession session = activeSessions.get(fileId);
        if (session == null) return;
        
        String json = P2PMessageProtocol.buildFileCancel(null, session.peerId, fileId, reason);
        PeerConnection conn = p2pManager.getOrCreateConnection(session.peerId);
        if (conn != null) {
            conn.sendTcp(json);
        }
        
        activeSessions.remove(fileId);
        
        if (listener != null) {
            listener.onFileCancelled(fileId, reason);
        }
    }
    
    // ===== GETTERS/SETTERS =====
    
    public void setListener(FileTransferListener listener) {
        this.listener = listener;
    }
    
    public FileTransferSession getSession(String fileId) {
        return activeSessions.get(fileId);
    }
    
    // ===== FILE TRANSFER SESSION =====
    
    public static class FileTransferSession {
        public final String fileId;
        public final File file;              // For sending
        public final String fileName;         // For receiving
        public final Long fileSize;
        public final Integer peerId;
        public final Integer conversationId;
        public final boolean isSending;
        
        // For receiving
        public Map<Integer, byte[]> receivedChunks;
        
        // For sending
        public FileTransferSession(String fileId, File file, Integer peerId, 
                                  Integer conversationId, boolean isSending) {
            this.fileId = fileId;
            this.file = file;
            this.fileName = file.getName();
            this.fileSize = file.length();
            this.peerId = peerId;
            this.conversationId = conversationId;
            this.isSending = isSending;
        }
        
        // For receiving
        public FileTransferSession(String fileId, String fileName, Long fileSize, 
                                  Integer peerId, Integer conversationId, boolean isSending) {
            this.fileId = fileId;
            this.file = null;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.peerId = peerId;
            this.conversationId = conversationId;
            this.isSending = isSending;
        }
    }
}