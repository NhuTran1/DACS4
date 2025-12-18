package network.p2p;

import protocol.P2PMessageProtocol;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * FileTransferManager - Quản lý việc gửi và nhận file qua P2P
 * - Chia file thành chunks
 * - Gửi tuần tự qua TCP
 * - Xử lý progress callback
 */
public class FileTransferManager {
    
    private static final int CHUNK_SIZE = 32 * 1024; // 32KB per chunk
    private static final String TEMP_DIR = "file_transfers";
    
    // Map: fileId -> FileTransfer
    private final Map<String, OutgoingTransfer> outgoingTransfers = new ConcurrentHashMap<>();
    private final Map<String, IncomingTransfer> incomingTransfers = new ConcurrentHashMap<>();
    
    private final P2PManager p2pManager;
    private FileTransferListener listener;
    
    public interface FileTransferListener {
        void onFileRequested(Integer fromUser, String fileId, String fileName, Long fileSize);
        void onFileAccepted(Integer fromUser, String fileId);
        void onFileRejected(Integer fromUser, String fileId, String reason);
        void onFileProgress(String fileId, int progress, boolean isUpload);
        void onFileComplete(String fileId, File file, boolean isUpload);
        void onFileCanceled(String fileId, boolean isUpload);
        void onFileError(String fileId, String error);
    }

    public FileTransferManager(P2PManager p2pManager) {
        this.p2pManager = p2pManager;
        createTempDirectory();
    }

    public void setListener(FileTransferListener listener) {
        this.listener = listener;
    }

    // ===== OUTGOING FILE TRANSFER =====
    
    /**
     * Gửi file request tới peer
     */
    public String sendFileRequest(Integer toUserId, File file) throws IOException {
        if (!file.exists() || !file.isFile()) {
            throw new IOException("File not found: " + file.getAbsolutePath());
        }

        String fileId = UUID.randomUUID().toString();
        String fileName = file.getName();
        Long fileSize = file.length();

        OutgoingTransfer transfer = new OutgoingTransfer(fileId, file, toUserId);
        outgoingTransfers.put(fileId, transfer);

        // Gửi file request
        String json = P2PMessageProtocol.buildFileRequest(
            p2pManager.getLocalUserId(), 
            toUserId, 
            fileName, 
            fileSize, 
            fileId
        );
        
        PeerConnection conn = p2pManager.getConnection(toUserId);
        if (conn == null || !conn.isTcpConnected()) {
            throw new IOException("Not connected to peer: " + toUserId);
        }
        
        conn.sendTcp(json);
        System.out.println("📤 Sent file request: " + fileName + " (" + formatSize(fileSize) + ")");
        
        return fileId;
    }

    /**
     * Xử lý file accept từ peer - bắt đầu gửi file
     */
    public void handleFileAccept(String fileId, Integer fromUserId) {
        OutgoingTransfer transfer = outgoingTransfers.get(fileId);
        if (transfer == null) {
            System.err.println("❌ Unknown file transfer: " + fileId);
            return;
        }

        transfer.status = TransferStatus.SENDING;
        
        // Thông báo UI
        if (listener != null) {
            listener.onFileAccepted(fromUserId, fileId);
        }

        // Bắt đầu gửi file trong background thread
        new Thread(() -> sendFileChunks(transfer, fromUserId), "file-sender-" + fileId).start();
    }

    /**
     * Gửi file thành từng chunks
     */
    private void sendFileChunks(OutgoingTransfer transfer, Integer toUserId) {
        try {
            byte[] buffer = new byte[CHUNK_SIZE];
            int totalChunks = (int) Math.ceil((double) transfer.file.length() / CHUNK_SIZE);
            int chunkIndex = 0;

            try (FileInputStream fis = new FileInputStream(transfer.file)) {
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) > 0 && 
                       transfer.status == TransferStatus.SENDING) {
                    
                    byte[] chunk = Arrays.copyOf(buffer, bytesRead);
                    
                    // Gửi chunk
                    String json = P2PMessageProtocol.buildFileChunk(
                        p2pManager.getLocalUserId(),
                        toUserId,
                        transfer.fileId,
                        chunkIndex,
                        chunk,
                        totalChunks
                    );
                    
                    PeerConnection conn = p2pManager.getConnection(toUserId);
                    if (conn == null || !conn.sendTcp(json)) {
                        throw new IOException("Failed to send chunk " + chunkIndex);
                    }

                    chunkIndex++;
                    int progress = (int) ((chunkIndex * 100.0) / totalChunks);
                    
                    // Update progress
                    if (listener != null) {
                        listener.onFileProgress(transfer.fileId, progress, true);
                    }
                    
                    // Small delay để không overwhelm network
                    Thread.sleep(10);
                }
            }

            if (transfer.status == TransferStatus.SENDING) {
                // Gửi complete message
                String json = P2PMessageProtocol.buildFileComplete(
                    p2pManager.getLocalUserId(),
                    toUserId,
                    transfer.fileId
                );
                
                PeerConnection conn = p2pManager.getConnection(toUserId);
                if (conn != null) {
                    conn.sendTcp(json);
                }

                transfer.status = TransferStatus.COMPLETED;
                
                if (listener != null) {
                    listener.onFileComplete(transfer.fileId, transfer.file, true);
                }
                
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
     * Xử lý file request từ peer
     */
    public void handleFileRequest(P2PMessageProtocol.Message msg) {
        String fileId = (String) msg.data.get("fileId");
        String fileName = (String) msg.data.get("fileName");
        Number fileSize = (Number) msg.data.get("fileSize");

        // Thông báo UI để user accept/reject
        if (listener != null) {
            listener.onFileRequested(msg.from, fileId, fileName, fileSize.longValue());
        }

        // Tạo incoming transfer object
        IncomingTransfer transfer = new IncomingTransfer(
            fileId, 
            fileName, 
            fileSize.longValue(), 
            msg.from
        );
        incomingTransfers.put(fileId, transfer);
    }

    /**
     * Accept nhận file
     */
    public void acceptFile(String fileId) {
        IncomingTransfer transfer = incomingTransfers.get(fileId);
        if (transfer == null) {
            System.err.println("❌ Unknown file transfer: " + fileId);
            return;
        }

        transfer.status = TransferStatus.RECEIVING;
        
        // Gửi accept message
        String json = P2PMessageProtocol.buildFileAccept(
            p2pManager.getLocalUserId(),
            transfer.fromUserId,
            fileId
        );
        
        PeerConnection conn = p2pManager.getConnection(transfer.fromUserId);
        if (conn != null) {
            conn.sendTcp(json);
        }
        
        System.out.println("✅ Accepted file: " + transfer.fileName);
    }

    /**
     * Reject nhận file
     */
    public void rejectFile(String fileId, String reason) {
        IncomingTransfer transfer = incomingTransfers.get(fileId);
        if (transfer == null) return;

        transfer.status = TransferStatus.REJECTED;
        
        String json = P2PMessageProtocol.buildFileReject(
            p2pManager.getLocalUserId(),
            transfer.fromUserId,
            fileId,
            reason
        );
        
        PeerConnection conn = p2pManager.getConnection(transfer.fromUserId);
        if (conn != null) {
            conn.sendTcp(json);
        }
        
        if (listener != null) {
            listener.onFileRejected(transfer.fromUserId, fileId, reason);
        }
        
        incomingTransfers.remove(fileId);
    }

    /**
     * Xử lý file chunk nhận được
     */
    public void handleFileChunk(P2PMessageProtocol.Message msg) throws IOException {
        String fileId = (String) msg.data.get("fileId");
        Number chunkIndex = (Number) msg.data.get("chunkIndex");
        Number totalChunks = (Number) msg.data.get("totalChunks");
        String chunkDataB64 = (String) msg.data.get("chunkData");

        IncomingTransfer transfer = incomingTransfers.get(fileId);
        if (transfer == null || transfer.status != TransferStatus.RECEIVING) {
            System.err.println("❌ Unexpected file chunk: " + fileId);
            return;
        }

        // Decode chunk data
        byte[] chunkData = Base64.getDecoder().decode(chunkDataB64);
        
        // Write to temp file
        if (transfer.outputStream == null) {
            Path tempPath = Paths.get(TEMP_DIR, transfer.fileName);
            transfer.outputStream = new FileOutputStream(tempPath.toFile());
        }
        
        transfer.outputStream.write(chunkData);
        transfer.receivedChunks++;

        int progress = (int) ((transfer.receivedChunks * 100.0) / totalChunks.intValue());
        
        if (listener != null) {
            listener.onFileProgress(fileId, progress, false);
        }
    }

    /**
     * Xử lý file complete
     */
    public void handleFileComplete(String fileId) {
        IncomingTransfer transfer = incomingTransfers.get(fileId);
        if (transfer == null) return;

        try {
            if (transfer.outputStream != null) {
                transfer.outputStream.close();
            }

            transfer.status = TransferStatus.COMPLETED;
            
            File receivedFile = new File(TEMP_DIR, transfer.fileName);
            
            if (listener != null) {
                listener.onFileComplete(fileId, receivedFile, false);
            }
            
            System.out.println("✅ File received successfully: " + transfer.fileName);

        } catch (IOException e) {
            System.err.println("❌ Error finalizing file: " + e.getMessage());
            
            if (listener != null) {
                listener.onFileError(fileId, e.getMessage());
            }
        } finally {
            incomingTransfers.remove(fileId);
        }
    }

    /**
     * Xử lý file cancel
     */
    public void handleFileCancel(String fileId) {
        IncomingTransfer transfer = incomingTransfers.get(fileId);
        if (transfer == null) return;

        try {
            if (transfer.outputStream != null) {
                transfer.outputStream.close();
            }
            
            // Delete incomplete file
            File tempFile = new File(TEMP_DIR, transfer.fileName);
            if (tempFile.exists()) {
                tempFile.delete();
            }

        } catch (IOException e) {
            System.err.println("⚠️ Error cleaning up canceled transfer: " + e.getMessage());
        }

        transfer.status = TransferStatus.CANCELED;
        
        if (listener != null) {
            listener.onFileCanceled(fileId, false);
        }
        
        incomingTransfers.remove(fileId);
    }

    // ===== HELPER METHODS =====
    
    private void createTempDirectory() {
        try {
            Files.createDirectories(Paths.get(TEMP_DIR));
        } catch (IOException e) {
            System.err.println("⚠️ Failed to create temp directory: " + e.getMessage());
        }
    }

    private String formatSize(Long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    public void shutdown() {
        // Close all ongoing transfers
        outgoingTransfers.values().forEach(t -> t.status = TransferStatus.CANCELED);
        incomingTransfers.values().forEach(t -> {
            t.status = TransferStatus.CANCELED;
            try {
                if (t.outputStream != null) t.outputStream.close();
            } catch (IOException ignored) {}
        });
        
        outgoingTransfers.clear();
        incomingTransfers.clear();
    }

    // ===== INNER CLASSES =====
    
    private enum TransferStatus {
        PENDING, SENDING, RECEIVING, COMPLETED, FAILED, CANCELED, REJECTED
    }

    private static class OutgoingTransfer {
        String fileId;
        File file;
        Integer toUserId;
        TransferStatus status = TransferStatus.PENDING;

        OutgoingTransfer(String fileId, File file, Integer toUserId) {
            this.fileId = fileId;
            this.file = file;
            this.toUserId = toUserId;
        }
    }

    private static class IncomingTransfer {
        String fileId;
        String fileName;
        Long fileSize;
        Integer fromUserId;
        TransferStatus status = TransferStatus.PENDING;
        FileOutputStream outputStream;
        int receivedChunks = 0;

        IncomingTransfer(String fileId, String fileName, Long fileSize, Integer fromUserId) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.fromUserId = fromUserId;
        }
    }
}