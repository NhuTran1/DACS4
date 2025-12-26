package network.p2p;

import util.FileChecksumUtil;

import protocol.P2PMessageProtocol;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * FileTransferManager - Simplified version (no request/accept/reject)
 * - Chia file thành chunks
 * - Gửi trực tiếp qua TCP
 * - Xử lý progress callback
 * - Hỗ trợ idempotent với clientMessageId
 */
public class FileTransferManager {
    
    private static final int CHUNK_SIZE = 32 * 1024; // 32KB per chunk
    
    // Map: fileId -> FileTransfer
    private final Map<String, OutgoingTransfer> outgoingTransfers = new ConcurrentHashMap<>();
    
    private final P2PManager p2pManager;
    private FileTransferListener listener;
    
    public interface FileTransferListener {
        void onFileProgress(String fileId, int progress, boolean isUpload);
        void onFileComplete(String fileId, File file, boolean isUpload);
        void onFileCanceled(String fileId, boolean isUpload);
        void onFileError(String fileId, String error);
    }

    public FileTransferManager(P2PManager p2pManager) {
        this.p2pManager = p2pManager;
    }

    public void setListener(FileTransferListener listener) {
        this.listener = listener;
    }

    // ===== OUTGOING FILE TRANSFER =====
    
    /**
     * Gửi file trực tiếp (không cần request/accept)
     */
    public String sendFile(Integer toUserId, File file, Integer conversationId, 
                          String clientMessageId) throws IOException {
        if (!file.exists() || !file.isFile()) {
            throw new IOException("File not found: " + file.getAbsolutePath());
        }

        String fileId = UUID.randomUUID().toString();
        String fileName = file.getName();
        Long fileSize = file.length();

        OutgoingTransfer transfer = new OutgoingTransfer(
            fileId, file, toUserId, conversationId, clientMessageId
        );
        outgoingTransfers.put(fileId, transfer);

        // Start sending immediately
        new Thread(() -> sendFileChunks(transfer), "file-sender-" + fileId).start();
        
        System.out.println("📤 Sending file: " + fileName + " (" + formatSize(fileSize) + ")");
        
        return fileId;
    }

    /**
     * Gửi file thành từng chunks
     */
    private void sendFileChunks(OutgoingTransfer transfer) {
        try {
            byte[] buffer = new byte[CHUNK_SIZE];
            int totalChunks = (int) Math.ceil((double) transfer.file.length() / CHUNK_SIZE);
            int chunkIndex = 0;
            
         // ✅ TÍNH CHECKSUM 1 LẦN
            transfer.checksum = FileChecksumUtil.sha256(transfer.file);

            try (FileInputStream fis = new FileInputStream(transfer.file)) {
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) > 0 && 
                       transfer.status == TransferStatus.SENDING) {
                    
                    byte[] chunk = Arrays.copyOf(buffer, bytesRead);
                    
                    // Build protocol message
//                    String json = P2PMessageProtocol.buildFileChunk(
//                        p2pManager.getLocalUserId(),
//                        transfer.toUserId,
//                        transfer.fileId,
//                        chunkIndex,
//                        chunk,
//                        totalChunks,
//                        transfer.file.getName(),
//                        transfer.file.length(),
//                        transfer.conversationId,
//                        transfer.clientMessageId,
//                        transfer.checksum 
//                    );
                    
//                    PeerConnection conn = p2pManager.getConnection(transfer.toUserId);
//                    if (conn == null || !conn.sendTcp(json)) {
//                        throw new IOException("Failed to send chunk " + chunkIndex);
//                    }
//
//                    chunkIndex++;
//                    int progress = (int) ((chunkIndex * 100.0) / totalChunks);
//                    
//                    // Update progress
//                    if (listener != null) {
//                        listener.onFileProgress(transfer.fileId, progress, true);
//                    }
                    
                    
                    // Small delay to avoid overwhelming network
                    Thread.sleep(10);
                }
            }

            if (transfer.status == TransferStatus.SENDING) {
                // Send complete message
                String json = P2PMessageProtocol.buildFileComplete(
                    p2pManager.getLocalUserId(),
                    transfer.toUserId,
                    transfer.fileId
                );
                
                PeerConnection conn = p2pManager.getConnection(transfer.toUserId);
                if (conn != null) {
                    conn.sendTcp(json);
                }

                transfer.status = TransferStatus.COMPLETED;
                
//                if (listener != null) {
//                    listener.onFileComplete(transfer.fileId, transfer.file, true);
//                }
                
                System.out.println("✅ File sent successfully: " + transfer.file.getName());
            }

        } catch (Exception e) {
            transfer.status = TransferStatus.FAILED;
            System.err.println("❌ Error sending file: " + e.getMessage());
            
            if (listener != null) {
                listener.onFileError(transfer.fileId, e.getMessage());
            }
        } finally {
            outgoingTransfers.remove(transfer.fileId);
        }
    }

    /**
     * Hủy việc gửi file
     */
    public void cancelOutgoingTransfer(String fileId, Integer toUserId) {
        OutgoingTransfer transfer = outgoingTransfers.get(fileId);
        if (transfer != null) {
            transfer.status = TransferStatus.CANCELED;
            
            String json = P2PMessageProtocol.buildFileCancel(
                p2pManager.getLocalUserId(),
                toUserId,
                fileId
            );
            
            PeerConnection conn = p2pManager.getConnection(toUserId);
            if (conn != null) {
                conn.sendTcp(json);
            }
            
            if (listener != null) {
                listener.onFileCanceled(fileId, true);
            }
            
            outgoingTransfers.remove(fileId);
        }
    }

    // ===== INCOMING FILE TRANSFER =====
    
    /**
     * Xử lý file chunk nhận được
     * Delegate to FileTransferController for actual file handling
     */
    public void handleFileChunk(P2PMessageProtocol.Message msg) {
        // This is now handled by FileTransferController
        // P2PManager will forward to FileTransferController
    }

    /**
     * Xử lý file complete
     */
    public void handleFileComplete(String fileId) {
        // Handled by FileTransferController
        if (listener != null) {
            listener.onFileComplete(fileId, null, false);
        }
    }

    /**
     * Xử lý file cancel
     */
    public void handleFileCancel(String fileId) {
        if (listener != null) {
            listener.onFileCanceled(fileId, false);
        }
    }

    // ===== HELPER METHODS =====
    
    private String formatSize(Long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    public void shutdown() {
        // Cancel all ongoing transfers
        outgoingTransfers.values().forEach(t -> t.status = TransferStatus.CANCELED);
        outgoingTransfers.clear();
    }

    // ===== INNER CLASSES =====
    
    private enum TransferStatus {
        SENDING, COMPLETED, FAILED, CANCELED
    }

    private static class OutgoingTransfer {
        String fileId;
        File file;
        Integer toUserId;
        Integer conversationId;
        String clientMessageId;
        TransferStatus status = TransferStatus.SENDING;
        String checksum;
        
        OutgoingTransfer(String fileId, File file, Integer toUserId, 
                        Integer conversationId, String clientMessageId) {
            this.fileId = fileId;
            this.file = file;
            this.toUserId = toUserId;
            this.conversationId = conversationId;
            this.clientMessageId = clientMessageId;
        }
    }
}