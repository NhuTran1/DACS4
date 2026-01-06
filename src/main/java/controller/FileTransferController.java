package controller;

import network.p2p.P2PManager;
import network.p2p.FileTransferManager;
import service.ChatService;
import dao.FileAttachmentDao;
import model.FileAttachment;
import model.FileAttachment.FileStatus;
import model.Message;
import model.Users;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FileTransferController - với Status tracking và Checksum verification
 * 
 * Flow:
 * SENDER:
 * 1. Insert message (PENDING)
 * 2. Insert file_attachment (UPLOADING)
 * 3. Calculate checksum
 * 4. Send chunks via P2P
 * 5. On complete: Update file_attachment (COMPLETED), message (SENT)
 * 6. On error: Update file_attachment (FAILED), message (FAILED)
 * 
 * RECEIVER:
 * 1. Receive chunks
 * 2. Assemble file
 * 3. Calculate checksum
 * 4. Verify checksum
 * 5. Save file locally
 * 6. Insert file_attachment (COMPLETED)
 * 7. Send ACK to sender
 */
public class FileTransferController {
    
    private final P2PManager p2pManager;
    private final ChatService chatService;
    private final FileAttachmentDao fileAttachmentDao;
    private final Integer currentUserId;
    private final ChatController chatController;
    
    // Storage config
    private static final String STORAGE_BASE_DIR = "file_storage";
    private static final String UPLOAD_DIR = STORAGE_BASE_DIR + "/uploads";
    private static final String DOWNLOAD_DIR = STORAGE_BASE_DIR + "/downloads";
    
    // Track pending file transfers
    private final Map<String, FileTransferContext> pendingTransfers = new ConcurrentHashMap<>();
    
    // Callbacks for UI
    private FileProgressCallback fileProgressCallback;
    private FileCompleteCallback fileCompleteCallback;
    private FileErrorCallback fileErrorCallback;
    
    public interface FileProgressCallback {
        void onProgress(String fileId, int progress);
    }
    
    public interface FileCompleteCallback {
        void onComplete(String fileId, File file, boolean isUpload);
    }
    
    public interface FileErrorCallback {
        void onError(String fileId, String error);
    }
    
    // Context for tracking file transfers
    private static class FileTransferContext {
        String fileId;
        Integer conversationId;
        Integer senderId;
        Integer receiverId;
        String fileName; // Original filename
        String finalFileName; // Final filename (may have timestamp if duplicate)
        Long fileSize;
        File sourceFile; // for upload
        String clientMessageId;
        String checksum; // SHA-256
        boolean isUpload;
        Integer messageId;
        Integer fileAttachmentId;
        volatile boolean completed = false;
        
        FileTransferContext(String fileId, Integer conversationId, Integer senderId, 
                          Integer receiverId, String fileName, Long fileSize, 
                          String clientMessageId, boolean isUpload) {
            this.fileId = fileId;
            this.conversationId = conversationId;
            this.senderId = senderId;
            this.receiverId = receiverId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.clientMessageId = clientMessageId;
            this.isUpload = isUpload;
            
        }
    }

    public FileTransferController(P2PManager p2pManager, ChatService chatService, Integer currentUserId, ChatController chatController) {
        this.p2pManager = p2pManager;
        this.chatService = chatService;
        this.currentUserId = currentUserId;
        this.fileAttachmentDao = new FileAttachmentDao();
        this.chatController = chatController;
        
        System.out.println("📂 Current Working Directory: " + System.getProperty("user.dir"));
        System.out.println("📂 Expected Download Path: " + Paths.get(DOWNLOAD_DIR).toAbsolutePath());
        
        initializeStorageDirectories();
    }

    /**
     * Khởi tạo thư mục lưu file
     */
    private void initializeStorageDirectories() {
        try {
            Files.createDirectories(Paths.get(STORAGE_BASE_DIR).toAbsolutePath());
            Files.createDirectories(Paths.get(UPLOAD_DIR).toAbsolutePath());
            Files.createDirectories(Paths.get(DOWNLOAD_DIR).toAbsolutePath());
            System.out.println("✅ Storage initialized at: " + Paths.get(STORAGE_BASE_DIR).toAbsolutePath());
        } catch (IOException e) {
            System.err.println("❌ Critical: Could not create storage: " + e.getMessage());
        }
    }


    // ===== PUBLIC API =====
    
    /**
     * Gửi file tới user trong conversation (với Status tracking & Checksum)
     */
    public void sendFile(Integer conversationId, Integer toUserId, File file) {
        if (file == null || !file.exists()) {
            notifyError(null, "File not found");
            return;
        }
     // 1. Generate unique IDs
        String fileId = UUID.randomUUID().toString();
        String clientMessageId = UUID.randomUUID().toString();
        
        FileAttachment savedAttachment;
        
     // ===== STEP 1: SAVE DB (KHÔNG DÍNH P2P) =====
        try {
            savedAttachment = chatService.saveFileMessageAndAttachment(
                    conversationId,
                    currentUserId,
                    file,
                    fileId,
                    clientMessageId
            );
        } catch (Exception e) {
            notifyError(fileId, "DB save failed: " + e.getMessage());
            return;
        }

     // ===== STEP 2: SEND P2P (ASYNC) =====
        new Thread(() -> {
            try {
                p2pManager.sendFile(
                    toUserId,
                    file,
                    conversationId,
                    clientMessageId,      
                    fileId
                );
            } catch (Exception e) {
                // ❌ TUYỆT ĐỐI KHÔNG rollback DB
                fileAttachmentDao.updateStatus(
                    savedAttachment.getId(),
                    FileAttachment.FileStatus.FAILED
                );

                notifyError(fileId, "P2P failed: " + e.getMessage());
            }
        }).start();
        
        
        
//        try {

//            // 2. Copy file to upload directory
//            String storedFileName = fileId + "_" + file.getName();
//            Path storagePath = Paths.get(UPLOAD_DIR, storedFileName);
//            Files.copy(file.toPath(), storagePath, StandardCopyOption.REPLACE_EXISTING);
//            
//            // 3. Calculate checksum BEFORE sending
//            String checksum = calculateChecksum(storagePath.toFile());
//            System.out.println("✅ File checksum calculated: " + checksum);
//            
//            // 4. Create message in DB (PENDING)
//            String fileUrl = "file://" + file.getName() + "|" + formatFileSize(file.length());
//            Message message = chatService.sendFileMessageIdempotent(
//                conversationId,
//                currentUserId,
//                file.getName(),
//                fileUrl,
//                clientMessageId
//            );
//            
//            if (message == null) {
//                System.err.println("❌ Failed to save file message");
//                notifyError(fileId, "Failed to save message to database");
//                return;
//            }
//            
//            // 5. Create file attachment metadata (UPLOADING status)
//            FileAttachment attachment = new FileAttachment();
//            attachment.setMessage(message);
//            attachment.setSender(chatService.getUserById(currentUserId));
//            attachment.setFileId(fileId);
//            attachment.setFileName(file.getName());
//            attachment.setFilePath(storagePath.toString());
//            attachment.setFileSize(file.length());
//            attachment.setMimeType(detectMimeType(file));
//            attachment.setStatus(FileStatus.UPLOADING);
//            attachment.setChecksum(checksum);
//            
//            FileAttachment savedAttachment = fileAttachmentDao.save(attachment);
//            
//            if (savedAttachment == null) {
//                System.err.println("❌ Failed to save file attachment");
//                notifyError(fileId, "Failed to save file metadata");
//                return;
//            }
//            
//            // 6. Track transfer context
//            FileTransferContext context = new FileTransferContext(
//                fileId, conversationId, currentUserId, toUserId,
//                file.getName(), file.length(), clientMessageId, true
//            );
//            context.sourceFile = storagePath.toFile();
//            context.checksum = checksum;
//            context.messageId = message.getId();
//            context.fileAttachmentId = savedAttachment.getId();
//            pendingTransfers.put(fileId, context);
//            
//            // 7. Send file via P2P (trực tiếp, không cần request/accept)
//            String p2pFileId = p2pManager.sendFile(
//                toUserId, 
//                storagePath.toFile(), 
//                conversationId, 
//                clientMessageId, 
//                fileId
//            );
//            
//            System.out.println("✅ File send initiated:");
//            System.out.println("   - FileId: " + fileId);
//            System.out.println("   - Message ID: " + message.getId());
//            System.out.println("   - Attachment ID: " + savedAttachment.getId());
//            System.out.println("   - Storage path: " + storagePath);
//            System.out.println("   - Checksum: " + checksum);
//            System.out.println("   - ClientMessageId: " + clientMessageId);
//            
//        } catch (Exception e) {
//            notifyError(null, "Failed to send file: " + e.getMessage());
//            e.printStackTrace();
//        }
    }

    /**
     * Hủy file transfer
     */
    public void cancelTransfer(String fileId, Integer toUserId) {
        try {
            FileTransferContext context = pendingTransfers.get(fileId);
            
            // Update status to CANCELED
            if (context != null && context.fileAttachmentId != null) {
                fileAttachmentDao.updateStatus(context.fileAttachmentId, FileStatus.CANCELED);
            }
            
            p2pManager.cancelFileTransfer(fileId, toUserId);
            pendingTransfers.remove(fileId);
            System.out.println("🚫 File transfer canceled: " + fileId);
            
        } catch (Exception e) {
            notifyError(fileId, "Failed to cancel transfer: " + e.getMessage());
        }
    }

    // ===== CALLBACK SETTERS =====
    
    public void setFileProgressCallback(FileProgressCallback callback) {
        this.fileProgressCallback = callback;
    }

    public void setFileCompleteCallback(FileCompleteCallback callback) {
        this.fileCompleteCallback = callback;
    }

    public void setFileErrorCallback(FileErrorCallback callback) {
        this.fileErrorCallback = callback;
    }

    // ===== INTERNAL METHODS =====
    
    private void notifyProgress(String fileId, int progress) {
        if (fileProgressCallback != null) {
            fileProgressCallback.onProgress(fileId, progress);
        }
    }

    private void notifyComplete(String fileId, File file, boolean isUpload) {
        if (fileCompleteCallback != null) {
            fileCompleteCallback.onComplete(fileId, file, isUpload);
        }
    }

    private void notifyError(String fileId, String error) {
        if (fileErrorCallback != null) {
            fileErrorCallback.onError(fileId, error);
        } else {
            System.err.println("❌ File transfer error: " + error);
        }
    }

    // ===== CHECKSUM CALCULATION =====
    
    /**
     * Calculate SHA-256 checksum of file
     */
    private String calculateChecksum(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        
        byte[] hashBytes = digest.digest();
        
        // Convert to hex string
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        
        return sb.toString();
    }
    
    /**
     * Verify file checksum
     */
    private boolean verifyChecksum(File file, String expectedChecksum) {
        try {
            String actualChecksum = calculateChecksum(file);
            boolean matches = actualChecksum.equals(expectedChecksum);
            
            if (matches) {
                System.out.println("✅ Checksum verified: " + actualChecksum);
            } else {
                System.err.println("❌ Checksum mismatch!");
                System.err.println("   Expected: " + expectedChecksum);
                System.err.println("   Actual:   " + actualChecksum);
            }
            
            return matches;
        } catch (Exception e) {
            System.err.println("❌ Checksum verification failed: " + e.getMessage());
            return false;
        }
    }

    // ===== HELPER METHODS =====
    
    private String formatFileSize(Long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private String detectMimeType(File file) {
        try {
            Path path = file.toPath();
            String mimeType = Files.probeContentType(path);
            if (mimeType != null) {
                return mimeType;
            }
        } catch (IOException e) {
            // Fallback to extension-based detection
        }
        
        // Fallback based on extension
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
        if (fileName.endsWith(".png")) return "image/png";
        if (fileName.endsWith(".gif")) return "image/gif";
        if (fileName.endsWith(".pdf")) return "application/pdf";
        if (fileName.endsWith(".doc")) return "application/msword";
        if (fileName.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (fileName.endsWith(".txt")) return "text/plain";
        if (fileName.endsWith(".zip")) return "application/zip";
        
        return "application/octet-stream";
    }

    // ===== PUBLIC METHODS FOR P2P EVENTS =====
    
    /**
     * ✅ Called when file chunk is received
     * - Lưu chunks trực tiếp với tên file gốc (không có fileId prefix)
     * - Nếu trùng tên, thêm timestamp
     * - Sử dụng metadata từ P2PManager nếu cần
     */
    public void handleFileChunk(Integer fromUserId, String fileId, int chunkIndex,
                           byte[] chunkData, int totalChunks, String fileName,
                           Long fileSize, Integer conversationId, String clientMessageId,
                           String expectedChecksum) {
    try {
        FileTransferContext context = pendingTransfers.get(fileId);

        // ✅ Init context (chunk đầu hoặc context mất)
        if (chunkIndex == 0 || context == null) {

            if (context == null) {
                context = new FileTransferContext(
                    fileId,
                    conversationId,
                    fromUserId,
                    currentUserId,
                    fileName,
                    fileSize,
                    clientMessageId,
                    false
                );
                context.checksum = expectedChecksum;
                context.finalFileName = determineFinalFileName(fileName);
                pendingTransfers.put(fileId, context);
            }
        }

        // ✅ Absolute path + ensure directory
        Path finalPath = Paths.get(DOWNLOAD_DIR, context.finalFileName).toAbsolutePath();
        Files.createDirectories(finalPath.getParent());

        if (chunkIndex == 0) {
            // ✅ Chunk đầu: tạo mới / ghi đè
            Files.write(
                finalPath,
                chunkData,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            );
        } else {
            // ✅ Các chunk sau: append
            Files.write(
                finalPath,
                chunkData,
                java.nio.file.StandardOpenOption.APPEND
            );
        }

        int progress = (int) (((chunkIndex + 1) * 100.0) / totalChunks);
        notifyProgress(fileId, progress);

    } catch (Exception e) {
        System.err.println("❌ Error writing chunk " + chunkIndex + ": " + e.getMessage());
        e.printStackTrace();
        notifyError(fileId, "Write error: " + e.getMessage());
    }
}

    
    /**
     * ✅ Determine final filename - add timestamp if duplicate exists
     */
    private String determineFinalFileName(String originalFileName) {
        Path basePath = Paths.get(DOWNLOAD_DIR, originalFileName);
        
        // ✅ If file doesn't exist, use original name
        if (!Files.exists(basePath)) {
            return originalFileName;
        }
        
        // ✅ File exists - add timestamp to avoid conflict
        String baseName = originalFileName;
        String extension = "";
        int lastDot = baseName.lastIndexOf('.');
        if (lastDot > 0) {
            extension = baseName.substring(lastDot);
            baseName = baseName.substring(0, lastDot);
        }
        
        String timestamp = String.valueOf(System.currentTimeMillis());
        String newFileName = baseName + "_" + timestamp + extension;
        
        System.out.println("⚠️ [FileTransferController] File exists, using: " + newFileName + 
                        " (original: " + originalFileName + ")");
        
        return newFileName;
    }

    /**
 * Called when file transfer completes
 */
public void handleFileComplete(String fileId) {
    FileTransferContext context = pendingTransfers.get(fileId);
    if (context == null) return;

    synchronized (context) {
        if (context.completed) return;
        context.completed = true;
    }

    if (!context.isUpload) {
        try {
            // ✅ Delay nhẹ để OS flush file
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}

            Path finalPath = Paths.get(DOWNLOAD_DIR, context.finalFileName).toAbsolutePath();
            File fileObj = finalPath.toFile();

            if (!fileObj.exists()) {
                System.err.println("❌ ERROR: File completed but NOT FOUND: " + finalPath);
                notifyError(fileId, "File missing after download");
                return;
            }

            // ✅ Checksum
            String receivedChecksum = calculateChecksum(fileObj);
            if (context.checksum != null && !context.checksum.isEmpty()) {
                if (!verifyChecksum(fileObj, context.checksum)) {
                    throw new IOException("Checksum mismatch");
                }
            }

            // ✅ Save message
            String fileUrl = "file://" + context.fileName + "|" + formatFileSize(context.fileSize);
            
         // 1️⃣ LẤY message đã có
//            Message msg = chatService.getMessageByClientId(context.clientMessageId);
//            if (msg == null) {
//                notifyError(fileId, "Message FILE not found");
//                return;
//            }
            Message msg = chatService.createFileMessageWithAttachment(
            	    context.conversationId,
            	    context.senderId,   // sender THỰC SỰ của file
            	    fileObj,
            	    fileId
            	);

        	if (msg == null) {
        	    notifyError(fileId, "Failed to create file message");
        	    return;
        	}


         // hiển thị trong UI
           // msg.setImageUrl(context.fileName);
            
         // 2️⃣ CHỈ TẠO FileAttachment
//            FileAttachment attachment = new FileAttachment();
//            attachment.setMessage(msg);
//            attachment.setSender(chatService.getUserById(context.senderId));
//            attachment.setFileId(fileId);
//            attachment.setFileName(context.fileName);
//            attachment.setFilePath(fileObj.getAbsolutePath()); // QUAN TRỌNG
//            attachment.setFileSize(fileObj.length());
//            attachment.setMimeType(detectMimeType(fileObj));
//            attachment.setChecksum(receivedChecksum);
//            attachment.setStatus(FileStatus.COMPLETED);
//
//            fileAttachmentDao.save(attachment);
            
            notifyComplete(fileId, fileObj, false);
            sendFileAck(fileId, context.senderId);

        } catch (Exception e) {
            notifyError(fileId, e.getMessage());
            sendFileNack(fileId, context.senderId, e.getMessage());
        }
    }

    pendingTransfers.remove(fileId);
    p2pManager.removeFileMetadata(fileId);
}


    public void handleFileCanceled(String fileId, boolean isUpload) {
        FileTransferContext context = pendingTransfers.remove(fileId);
        
        if (context != null) {
            // Update status to CANCELED
            if (context.fileAttachmentId != null) {
                fileAttachmentDao.updateStatus(context.fileAttachmentId, FileStatus.CANCELED);
            }
            
            if (!isUpload) {
                // ✅ Clean up incomplete download (file saved with final filename)
                try {
                    String finalFileName = context.finalFileName != null ? context.finalFileName : context.fileName;
                    Path finalPath = Paths.get(DOWNLOAD_DIR, finalFileName);
                    if (Files.exists(finalPath)) {
                        Files.delete(finalPath);
                        System.out.println("🗑️ [FileTransferController] Deleted canceled file: " + finalPath);
                    }
                } catch (IOException e) {
                    System.err.println("⚠️ [FileTransferController] Error cleaning up canceled file: " + e.getMessage());
                }
            }
            
            // ✅ Clean up metadata
            p2pManager.removeFileMetadata(fileId);
        }
        
        notifyError(fileId, "Transfer canceled");
    }

    public void handleFileProgress(String fileId, int progress, boolean isUpload) {
        notifyProgress(fileId, progress);
    }

    public void handleFileError(String fileId, String error) {
        FileTransferContext context = pendingTransfers.remove(fileId);
        
        if (context != null && context.fileAttachmentId != null) {
            // Update status to FAILED
            fileAttachmentDao.updateStatus(context.fileAttachmentId, FileStatus.FAILED);
            
            // Update message status to FAILED
            if (context.messageId != null) {
                dao.MessageDao messageDao = new dao.MessageDao();
                messageDao.updateMessageStatus(context.messageId, model.Message.MessageStatus.FAILED);
            }
        }
        
        notifyError(fileId, error);
    }
    
    /**
     * ✅ Send FILE_ACK to sender
     */
    private void sendFileAck(String fileId, Integer toUserId) {
        try {
            String ackJson = protocol.P2PMessageProtocol.buildFileAck(
                currentUserId,
                toUserId,
                fileId
            );
            
            network.p2p.PeerConnection conn = p2pManager.getConnection(toUserId);
            if (conn != null && conn.isTcpConnected()) {
                conn.sendTcp(ackJson);
                System.out.println("✅ Sent FILE_ACK for: " + fileId);
            }
        } catch (Exception e) {
            System.err.println("❌ Failed to send FILE_ACK: " + e.getMessage());
        }
    }
    
    /**
     * ✅ Send FILE_NACK to sender
     */
    private void sendFileNack(String fileId, Integer toUserId, String reason) {
        try {
            String nackJson = protocol.P2PMessageProtocol.buildFileNack(
                currentUserId,
                toUserId,
                fileId,
                reason
            );
            
            network.p2p.PeerConnection conn = p2pManager.getConnection(toUserId);
            if (conn != null && conn.isTcpConnected()) {
                conn.sendTcp(nackJson);
                System.out.println("✅ Sent FILE_NACK for: " + fileId);
            }
        } catch (Exception e) {
            System.err.println("❌ Failed to send FILE_NACK: " + e.getMessage());
        }
    }
    

    // ===== PUBLIC QUERIES =====
    
    /**
     * Get file attachment by fileId
     */
    public FileAttachment getFileAttachment(String fileId) {
        return fileAttachmentDao.findByFileId(fileId);
    }

    /**
     * Get all files in conversation
     */
    public java.util.List<FileAttachment> getConversationFiles(Integer conversationId) {
        return fileAttachmentDao.findByConversationId(conversationId);
    }

    /**
     * Get user's total file storage usage
     */
    public Long getUserStorageUsage(Integer userId) {
        return fileAttachmentDao.getTotalSizeByUser(userId);
    }
    
    /**
     * Get uploading files for retry
     */
    public java.util.List<FileAttachment> getUploadingFiles() {
        return fileAttachmentDao.getUploadingFiles(currentUserId);
    }

    // ===== CLEANUP =====
    
    public void shutdown() {
        pendingTransfers.clear();
        System.out.println("✅ FileTransferController shutdown");
    }
}