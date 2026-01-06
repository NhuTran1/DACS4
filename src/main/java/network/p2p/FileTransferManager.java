package network.p2p;

import util.FileChecksumUtil;

import protocol.P2PMessageProtocol;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * - Chia file thành chunks
 * - Gửi trực tiếp qua TCP
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
                          String clientMessageId, String fileId) throws IOException {
    	 if (file == null || !file.exists() || !file.isFile()) {
    	        System.err.println("❌ File not found: " + file);
    	        if (listener != null) {
    	            listener.onFileError(fileId, "File not found");
    	        }
    	        return null; // ❌ KHÔNG THROW
    	    }

//        String fileId = UUID.randomUUID().toString();
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
     * Gửi file thành từng chunks (đã fix)
     * - Đảm bảo file gửi là file đã copy vào UPLOAD_DIR
     * - Dùng BufferedInputStream để đọc nhanh & ổn định
     * - Tính checksum 1 lần duy nhất
     */
    private void sendFileChunks(OutgoingTransfer transfer) {
        // ✅ Đảm bảo gửi đúng file đã được copy vào uploads
        File fileToSend = transfer.file;

        if (fileToSend == null || !fileToSend.exists() || !fileToSend.isFile()) {
            String err = "Source file not found: " +
                    (fileToSend != null ? fileToSend.getAbsolutePath() : "null");
            System.err.println("❌ Sender Error: " + err);
            if (listener != null) {
                listener.onFileError(transfer.fileId, "Source file not found");
            }
            return;
        }

        try {
            // ✅ Tính checksum 1 lần
            transfer.checksum = FileChecksumUtil.sha256(fileToSend);

            long fileSize = fileToSend.length();
            int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
            int chunkIndex = 0;

            System.out.println("📤 Start sending file:");
            System.out.println("   - File: " + fileToSend.getName());
            System.out.println("   - Size: " + formatSize(fileSize));
            System.out.println("   - Total chunks: " + totalChunks);
            System.out.println("   - Checksum: " + transfer.checksum.substring(0, 16) + "...");

            // ✅ Dùng BufferedInputStream để đọc ổn định
            try (BufferedInputStream bis =
                         new BufferedInputStream(new FileInputStream(fileToSend))) {

                byte[] buffer = new byte[CHUNK_SIZE];
                int bytesRead;

                while ((bytesRead = bis.read(buffer)) > 0 &&
                        transfer.status == TransferStatus.SENDING) {

                    byte[] chunk = Arrays.copyOf(buffer, bytesRead);

                    String json = P2PMessageProtocol.buildFileChunk(
                            p2pManager.getLocalUserId(),
                            transfer.toUserId,
                            transfer.fileId,
                            chunkIndex,
                            chunk,
                            totalChunks,
                            fileToSend.getName(),
                            fileSize,
                            transfer.conversationId,
                            transfer.clientMessageId,
                            transfer.checksum
                    );

                    PeerConnection conn = p2pManager.getConnection(transfer.toUserId);
                    if (conn == null || !conn.sendTcp(json)) {
                        String err = "Failed to send chunk " + chunkIndex;
                        System.err.println("❌ " + err);

                        transfer.status = TransferStatus.FAILED;

                        if (listener != null) {
                            listener.onFileError(transfer.fileId, err);
                        }
                        return; 
                    }

                    chunkIndex++;

                    int progress = (int) ((chunkIndex * 100.0) / totalChunks);
                    if (listener != null) {
                        listener.onFileProgress(transfer.fileId, progress, true);
                    }

                    // ✅ Nhẹ nhàng với network
                    Thread.sleep(10);
                }
            }

            // ✅ Gửi FILE_COMPLETE nếu chưa bị cancel
            if (transfer.status == TransferStatus.SENDING) {
                String completeJson = P2PMessageProtocol.buildFileComplete(
                        p2pManager.getLocalUserId(),
                        transfer.toUserId,
                        transfer.fileId
                );

                PeerConnection conn = p2pManager.getConnection(transfer.toUserId);
                if (conn != null) {
                    conn.sendTcp(completeJson);
                }

                transfer.status = TransferStatus.COMPLETED;
                System.out.println("✅ File sent successfully: " + fileToSend.getName());
            }

        } catch (Exception e) {
            transfer.status = TransferStatus.FAILED;
            System.err.println("❌ Error sending file: " + e.getMessage());
            e.printStackTrace();

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