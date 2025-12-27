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
        String fileName;
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
        
        initializeStorageDirectories();
    }

    /**
     * Khởi tạo thư mục lưu file
     */
    private void initializeStorageDirectories() {
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
            Files.createDirectories(Paths.get(DOWNLOAD_DIR));
            System.out.println("✅ File storage directories initialized");
        } catch (IOException e) {
            System.err.println("❌ Failed to create storage directories: " + e.getMessage());
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

        try {
            // 1. Generate unique IDs
            String fileId = UUID.randomUUID().toString();
            String clientMessageId = UUID.randomUUID().toString();
            
            // 2. Copy file to upload directory
            String storedFileName = fileId + "_" + file.getName();
            Path storagePath = Paths.get(UPLOAD_DIR, storedFileName);
            Files.copy(file.toPath(), storagePath, StandardCopyOption.REPLACE_EXISTING);
            
            // 3. Calculate checksum BEFORE sending
            String checksum = calculateChecksum(storagePath.toFile());
            System.out.println("✅ File checksum calculated: " + checksum);
            
            // 4. Create message in DB (PENDING)
            String fileUrl = "file://" + file.getName() + "|" + formatFileSize(file.length());
            Message message = chatService.sendFileMessageIdempotent(
                conversationId,
                currentUserId,
                file.getName(),
                fileUrl,
                clientMessageId
            );
            
            if (message == null) {
                System.err.println("❌ Failed to save file message");
                notifyError(fileId, "Failed to save message to database");
                return;
            }
            
            // 5. Create file attachment metadata (UPLOADING status)
            FileAttachment attachment = new FileAttachment();
            attachment.setMessage(message);
            attachment.setSender(chatService.getUserById(currentUserId));
            attachment.setFileId(fileId);
            attachment.setFileName(file.getName());
            attachment.setFilePath(storagePath.toString());
            attachment.setFileSize(file.length());
            attachment.setMimeType(detectMimeType(file));
            attachment.setStatus(FileStatus.UPLOADING);
            attachment.setChecksum(checksum);
            
            FileAttachment savedAttachment = fileAttachmentDao.save(attachment);
            
            if (savedAttachment == null) {
                System.err.println("❌ Failed to save file attachment");
                notifyError(fileId, "Failed to save file metadata");
                return;
            }
            
            // 6. Track transfer context
            FileTransferContext context = new FileTransferContext(
                fileId, conversationId, currentUserId, toUserId,
                file.getName(), file.length(), clientMessageId, true
            );
            context.sourceFile = storagePath.toFile();
            context.checksum = checksum;
            context.messageId = message.getId();
            context.fileAttachmentId = savedAttachment.getId();
            pendingTransfers.put(fileId, context);
            
            // 7. Send file via P2P (trực tiếp, không cần request/accept)
            String p2pFileId = p2pManager.sendFile(
                toUserId, 
                storagePath.toFile(), 
                conversationId, 
                clientMessageId
            );
            
            System.out.println("✅ File send initiated:");
            System.out.println("   - FileId: " + fileId);
            System.out.println("   - Message ID: " + message.getId());
            System.out.println("   - Attachment ID: " + savedAttachment.getId());
            System.out.println("   - Storage path: " + storagePath);
            System.out.println("   - Checksum: " + checksum);
            System.out.println("   - ClientMessageId: " + clientMessageId);
            
        } catch (Exception e) {
            notifyError(null, "Failed to send file: " + e.getMessage());
            e.printStackTrace();
        }
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
     * Called when file chunk is received (first chunk contains metadata + checksum)
     */
    public void handleFileChunk(Integer fromUserId, String fileId, int chunkIndex, 
                               byte[] chunkData, int totalChunks, String fileName, 
                               Long fileSize, Integer conversationId, String clientMessageId,
                               String expectedChecksum) {
        try {
            FileTransferContext context = pendingTransfers.get(fileId);
            
            // First chunk - initialize context
            if (chunkIndex == 0) {
                context = new FileTransferContext(
                    fileId, conversationId, fromUserId, currentUserId,
                    fileName, fileSize, clientMessageId, false
                );
                context.checksum = expectedChecksum; // Store expected checksum
                pendingTransfers.put(fileId, context);
                
                System.out.println("📥 Receiving file: " + fileName);
                System.out.println("   - FileId: " + fileId);
                System.out.println("   - Size: " + formatFileSize(fileSize));
                System.out.println("   - Expected Checksum: " + expectedChecksum);
                System.out.println("   - ClientMessageId: " + clientMessageId);
            }
            
            if (context == null) {
                System.err.println("❌ Unexpected file chunk: " + fileId);
                return;
            }
            
            // Write chunk to temp file
            String tempFileName = fileId + "_" + fileName;
            Path tempPath = Paths.get(DOWNLOAD_DIR, tempFileName);
            
            if (chunkIndex == 0) {
                // Create new file
                Files.write(tempPath, chunkData);
            } else {
                // Append to existing file
                Files.write(tempPath, chunkData, 
                    java.nio.file.StandardOpenOption.APPEND);
            }
            
            // Calculate progress
            int progress = (int) (((chunkIndex + 1) * 100.0) / totalChunks);
            notifyProgress(fileId, progress);
            
        } catch (Exception e) {
            System.err.println("❌ Error handling file chunk: " + e.getMessage());
            notifyError(fileId, e.getMessage());
        }
    }

    /**
 * Called when file transfer completes
 */
public void handleFileComplete(String fileId) {
    FileTransferContext context = pendingTransfers.get(fileId);
    
    if (context == null) {
        System.err.println("⚠️ FILE_COMPLETE but context missing: " + fileId);
        return;
    }
    
    // ✅ GUARD: chỉ cho chạy 1 lần
    synchronized (context) {
        if (context.completed) {
            System.out.println("⚠️ FILE_COMPLETE already handled: " + fileId);
            return;
        }
        context.completed = true;
    }
    
    if (!context.isUpload) {
        // ===== RECEIVER: Verify, save, and CREATE MESSAGE =====
        try {
            String tempFileName = fileId + "_" + context.fileName;
            Path tempPath = Paths.get(DOWNLOAD_DIR, tempFileName);
            
            if (!Files.exists(tempPath)) {
                throw new IOException("File not found: " + tempPath);
            }
            
            // Calculate checksum of received file
            String receivedChecksum = calculateChecksum(tempPath.toFile());
            
            // Verify checksum if provided
            boolean checksumValid = true;
            if (context.checksum != null && !context.checksum.isEmpty()) {
                checksumValid = verifyChecksum(tempPath.toFile(), context.checksum);
                
                if (!checksumValid) {
                    throw new IOException("Checksum verification failed! File may be corrupted.");
                }
            }
            
            System.out.println("✅ RECEIVER: File received successfully:");
            System.out.println("   - FileId: " + fileId);
            System.out.println("   - Path: " + tempPath);
            System.out.println("   - Size: " + context.fileSize);
            System.out.println("   - Checksum: " + receivedChecksum);
            
            if (checksumValid) {
                System.out.println("✅ Checksum verified successfully");
            }
            
            // 1. CREATE MESSAGE IN DB (RECEIVER SIDE)
            String fileUrl = "file://" + context.fileName + "|" + formatFileSize(context.fileSize);
            
            Message msg = chatService.sendFileMessageIdempotent(
                context.conversationId,
                context.senderId,  // SENDER ID (the one who sent the file)
                context.fileName,
                fileUrl,
                context.clientMessageId
            );
            
            if (msg == null) {
                System.err.println("❌ RECEIVER: Failed to create file message in DB");
            } else {
                System.out.println("✅ RECEIVER: Created file message in DB (ID: " + msg.getId() + ")");
            }
            
            // ✅ 2. Copy file to final location with original name (for easy access)
            Path finalPath = null;
            try {
                // Copy file from temp location to final location with original name
                finalPath = Paths.get(DOWNLOAD_DIR, context.fileName);
                
                // Handle duplicate file names by adding number suffix
                int counter = 1;
                String baseName = context.fileName;
                String extension = "";
                int lastDot = baseName.lastIndexOf('.');
                if (lastDot > 0) {
                    extension = baseName.substring(lastDot);
                    baseName = baseName.substring(0, lastDot);
                }
                
                while (Files.exists(finalPath)) {
                    String newName = baseName + "_" + counter + extension;
                    finalPath = Paths.get(DOWNLOAD_DIR, newName);
                    counter++;
                }
                
                Files.copy(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("✅ RECEIVER: File copied to final location: " + finalPath);
                
                // Optionally: Delete temp file to save space
                // Files.delete(tempPath);
                
            } catch (Exception e) {
                System.err.println("⚠️ RECEIVER: Failed to copy file to final location: " + e.getMessage());
                // Continue with temp path if copy fails
                finalPath = tempPath;
            }

            // ✅ 3. Create FileAttachment with final path
            FileAttachment attachment = new FileAttachment();
            attachment.setMessage(msg);
            attachment.setSender(chatService.getUserById(context.senderId));
            attachment.setFileId(fileId);
            attachment.setFileName(context.fileName);
            attachment.setFilePath(finalPath != null ? finalPath.toString() : tempPath.toString());
            attachment.setFileSize(context.fileSize);
            attachment.setMimeType(detectMimeType(finalPath != null ? finalPath.toFile() : tempPath.toFile()));
            attachment.setStatus(FileStatus.COMPLETED);
            attachment.setChecksum(receivedChecksum);
            
            FileAttachment savedAttachment = fileAttachmentDao.save(attachment);
            if (savedAttachment != null) {
                System.out.println("✅ RECEIVER: FileAttachment saved (ID: " + savedAttachment.getId() + ")");
            }

            // 2. Notify UI to display message
            if (msg != null && chatController != null) {
                chatController.handleIncomingMessage(context.conversationId, msg);
                System.out.println("✅ RECEIVER: Notified UI to display file message");
            }
            
            // ✅ 4. Notify UI to display message
            if (msg != null && chatController != null) {
                chatController.handleIncomingMessage(context.conversationId, msg);
                System.out.println("✅ RECEIVER: Notified UI to display file message");
            }
            
            // ✅ 5. Notify listener with FILE OBJECT (use final path)
            notifyComplete(fileId, finalPath != null ? finalPath.toFile() : tempPath.toFile(), false);
            
            // ✅ 6. Send FILE_ACK back to sender
            sendFileAck(fileId, context.senderId);
           
        } catch (Exception e) {
            System.err.println("❌ RECEIVER: Error saving received file: " + e.getMessage());
            e.printStackTrace();
            
            // Send FILE_NACK back to sender
            sendFileNack(fileId, context.senderId, e.getMessage());
            
            notifyError(fileId, "Failed to save file: " + e.getMessage());
        }
    } else {
        // ===== SENDER: Just update status =====
        try {
            System.out.println("✅ SENDER: File sent successfully:");
            System.out.println("   - File ID: " + fileId);
            System.out.println("   - Message ID: " + context.messageId);
            System.out.println("   - Attachment ID: " + context.fileAttachmentId);
            
            // Notify listener (for progress dialog)
            notifyComplete(fileId, context.sourceFile, true);
            
            // Message already created when user clicked send
            // No need to create again here
            
        } catch (Exception e) {
            System.err.println("❌ Error updating file status: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    pendingTransfers.remove(fileId);
}

    public void handleFileCanceled(String fileId, boolean isUpload) {
        FileTransferContext context = pendingTransfers.remove(fileId);
        
        if (context != null) {
            // Update status to CANCELED
            if (context.fileAttachmentId != null) {
                fileAttachmentDao.updateStatus(context.fileAttachmentId, FileStatus.CANCELED);
            }
            
            if (!isUpload) {
                // Clean up incomplete download
                try {
                    String tempFileName = fileId + "_" + context.fileName;
                    Path tempPath = Paths.get(DOWNLOAD_DIR, tempFileName);
                    if (Files.exists(tempPath)) {
                        Files.delete(tempPath);
                    }
                } catch (IOException e) {
                    System.err.println("⚠️ Error cleaning up canceled file: " + e.getMessage());
                }
            }
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