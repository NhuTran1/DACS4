package controller;

import network.p2p.P2PManager;
import network.p2p.FileTransferManager;
import service.ChatService;
import dao.FileAttachmentDao;
import dao.MessageDao;
import model.FileAttachment;
import model.Message;
import model.Users;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FileTransferController
 * 
 * Trách nhiệm:
 * - Quản lý việc gửi/nhận file qua P2P
 * - Theo dõi progress
 * - Xử lý resume/cancel
 * - Lưu metadata file vào DB
 * - Quản lý file storage trên disk
 * 
 * Phụ thuộc:
 * - P2PManager
 * - FileTransferManager
 * - ChatService (để lưu file message)
 * - FileAttachmentDao
 * 
 * KHÔNG phụ thuộc UI
 */
public class FileTransferController {
    
    private final P2PManager p2pManager;
    private final ChatService chatService;
    private final FileAttachmentDao fileAttachmentDao;
    private final MessageDao messageDao;
    private final Integer currentUserId;
    
    // Storage config
    private static final String STORAGE_BASE_DIR = "file_storage";
    private static final String UPLOAD_DIR = STORAGE_BASE_DIR + "/uploads";
    private static final String DOWNLOAD_DIR = STORAGE_BASE_DIR + "/downloads";
    
    // Track pending file transfers
    private final Map<String, FileTransferContext> pendingTransfers = new ConcurrentHashMap<>();
    
    // Callbacks for UI
    private FileRequestCallback fileRequestCallback;
    private FileProgressCallback fileProgressCallback;
    private FileCompleteCallback fileCompleteCallback;
    private FileErrorCallback fileErrorCallback;
    
    public interface FileRequestCallback {
        void onFileRequested(Integer fromUserId, String fileId, String fileName, Long fileSize);
    }
    
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
        boolean isUpload;
        
        FileTransferContext(String fileId, Integer conversationId, Integer senderId, 
                          Integer receiverId, String fileName, Long fileSize, boolean isUpload) {
            this.fileId = fileId;
            this.conversationId = conversationId;
            this.senderId = senderId;
            this.receiverId = receiverId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.isUpload = isUpload;
        }
    }

    public FileTransferController(P2PManager p2pManager, ChatService chatService, Integer currentUserId) {
        this.p2pManager = p2pManager;
        this.chatService = chatService;
        this.currentUserId = currentUserId;
        this.fileAttachmentDao = new FileAttachmentDao();
        this.messageDao = new MessageDao();
        
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
     * Gửi file tới user trong conversation
     */
    public void sendFile(Integer conversationId, Integer toUserId, File file) {
        if (file == null || !file.exists()) {
            notifyError(null, "File not found");
            return;
        }

        try {
            // 1. Generate unique file ID
            String fileId = UUID.randomUUID().toString();
            
            // 2. Copy file to upload directory
            String storedFileName = fileId + "_" + file.getName();
            Path storagePath = Paths.get(UPLOAD_DIR, storedFileName);
            Files.copy(file.toPath(), storagePath, StandardCopyOption.REPLACE_EXISTING);
            
            // 3. Create message in DB (SENDER)
            Message message = new Message();
            message.setConversation(chatService.getConversationById(conversationId));
            message.setSender(chatService.getUserById(currentUserId));
            message.setMessageType(Message.MessageType.FILE);
            message.setContent("[File] " + file.getName());
            messageDao.saveMessage(message);
            
            // 4. Create file attachment metadata
            FileAttachment attachment = new FileAttachment();
            attachment.setMessage(message);
            attachment.setSender(chatService.getUserById(currentUserId));
            attachment.setFileId(fileId);
            attachment.setFileName(file.getName());
            attachment.setFilePath(storagePath.toString());
            attachment.setFileSize(file.length());
            attachment.setMimeType(detectMimeType(file));
            
            fileAttachmentDao.save(attachment);
            
            // 5. Track transfer context
            FileTransferContext context = new FileTransferContext(
                fileId, conversationId, currentUserId, toUserId,
                file.getName(), file.length(), true
            );
            context.sourceFile = storagePath.toFile();
            pendingTransfers.put(fileId, context);
            
            // 6. Send file via P2P
            String p2pFileId = p2pManager.sendFile(toUserId, storagePath.toFile());
            
            System.out.println("✅ File send initiated: " + fileId);
            System.out.println("   - Message ID: " + message.getId());
            System.out.println("   - Storage path: " + storagePath);
            
        } catch (Exception e) {
            notifyError(null, "Failed to send file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Chấp nhận nhận file
     */
    public void acceptFile(String fileId) {
        try {
            p2pManager.acceptFile(fileId);
            System.out.println("✅ File accepted: " + fileId);
        } catch (Exception e) {
            notifyError(fileId, "Failed to accept file: " + e.getMessage());
        }
    }

    /**
     * Từ chối nhận file
     */
    public void rejectFile(String fileId, String reason) {
        try {
            p2pManager.rejectFile(fileId, reason);
            pendingTransfers.remove(fileId);
            System.out.println("❌ File rejected: " + fileId);
        } catch (Exception e) {
            System.err.println("Error rejecting file: " + e.getMessage());
        }
    }

    /**
     * Hủy file transfer
     */
    public void cancelTransfer(String fileId, Integer toUserId) {
        try {
            p2pManager.cancelFileTransfer(fileId, toUserId);
            pendingTransfers.remove(fileId);
            System.out.println("🚫 File transfer canceled: " + fileId);
        } catch (Exception e) {
            notifyError(fileId, "Failed to cancel transfer: " + e.getMessage());
        }
    }

    // ===== CALLBACK SETTERS =====
    
    public void setFileRequestCallback(FileRequestCallback callback) {
        this.fileRequestCallback = callback;
    }

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
    
    private void notifyFileRequest(Integer fromUserId, String fileId, String fileName, Long fileSize) {
        if (fileRequestCallback != null) {
            fileRequestCallback.onFileRequested(fromUserId, fileId, fileName, fileSize);
        }
    }

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

    // ===== PUBLIC METHODS FOR P2P EVENTS (called from ChatController) =====
    
    /**
     * Called when file request is received
     */
    public void handleFileRequest(Integer fromUserId, String fileId, String fileName, Long fileSize) {
        // Track context for receiver
        // Note: conversationId will be set when file is accepted
        FileTransferContext context = new FileTransferContext(
            fileId, null, fromUserId, currentUserId,
            fileName, fileSize, false
        );
        pendingTransfers.put(fileId, context);
        
        notifyFileRequest(fromUserId, fileId, fileName, fileSize);
    }

    /**
     * Called when file is accepted
     */
    public void handleFileAccepted(Integer fromUserId, String fileId) {
        System.out.println("✅ File accepted by peer: " + fileId);
    }

    /**
     * Called when file is rejected
     */
    public void handleFileRejected(Integer fromUserId, String fileId, String reason) {
        pendingTransfers.remove(fileId);
        notifyError(fileId, "File rejected: " + reason);
    }

    /**
     * Called when file progress updates
     */
    public void handleFileProgress(String fileId, int progress, boolean isUpload) {
        notifyProgress(fileId, progress);
    }

    /**
     * Called when file transfer completes
     */
    public void handleFileComplete(String fileId, File receivedFile, boolean isUpload) {
        FileTransferContext context = pendingTransfers.get(fileId);
        
        if (!isUpload && context != null) {
            // RECEIVER: Save file to download directory and create DB records
            try {
                // 1. Move file to permanent storage
                String storedFileName = fileId + "_" + context.fileName;
                Path storagePath = Paths.get(DOWNLOAD_DIR, storedFileName);
                Files.move(receivedFile.toPath(), storagePath, StandardCopyOption.REPLACE_EXISTING);
                
                // 2. Find or create message
                // In real scenario, message should be created by sender and synced
                // For now, create placeholder message for receiver
                Message message = createReceiverFileMessage(context, storagePath);
                
                if (message != null) {
                    // 3. Create file attachment metadata
                    FileAttachment attachment = new FileAttachment();
                    attachment.setMessage(message);
                    attachment.setSender(chatService.getUserById(context.senderId));
                    attachment.setFileId(fileId);
                    attachment.setFileName(context.fileName);
                    attachment.setFilePath(storagePath.toString());
                    attachment.setFileSize(context.fileSize);
                    attachment.setMimeType(detectMimeType(storagePath.toFile()));
                    
                    fileAttachmentDao.save(attachment);
                    
                    System.out.println("✅ File received and saved:");
                    System.out.println("   - File ID: " + fileId);
                    System.out.println("   - Message ID: " + message.getId());
                    System.out.println("   - Storage path: " + storagePath);
                }
                
                notifyComplete(fileId, storagePath.toFile(), false);
                
            } catch (Exception e) {
                System.err.println("❌ Error saving received file: " + e.getMessage());
                e.printStackTrace();
                notifyError(fileId, "Failed to save file: " + e.getMessage());
            }
        } else {
            // SENDER: File sent successfully
            notifyComplete(fileId, receivedFile, true);
        }
        
        pendingTransfers.remove(fileId);
    }

    /**
     * Create message for receiver when file arrives
     */
    private Message createReceiverFileMessage(FileTransferContext context, Path storagePath) {
        try {
            // Get or find conversation
            // In real app, conversation should be part of context
            // For now, try to find direct conversation with sender
            Integer conversationId = context.conversationId;
            
            if (conversationId == null) {
                // Try to find direct conversation
                model.Conversation conv = chatService.getDirectConversation(
                    context.senderId, currentUserId
                );
                if (conv != null) {
                    conversationId = conv.getId();
                }
            }
            
            if (conversationId == null) {
                System.err.println("⚠️ Cannot determine conversation for file message");
                return null;
            }
            
            Message message = new Message();
            message.setConversation(chatService.getConversationById(conversationId));
            message.setSender(chatService.getUserById(context.senderId));
            message.setMessageType(Message.MessageType.FILE);
            message.setContent("[File] " + context.fileName);
            
            messageDao.saveMessage(message);
            return message;
            
        } catch (Exception e) {
            System.err.println("❌ Error creating receiver file message: " + e.getMessage());
            return null;
        }
    }

    /**
     * Called when file transfer is canceled
     */
    public void handleFileCanceled(String fileId, boolean isUpload) {
        pendingTransfers.remove(fileId);
        notifyError(fileId, "Transfer canceled");
    }

    /**
     * Called when file transfer error occurs
     */
    public void handleFileError(String fileId, String error) {
        pendingTransfers.remove(fileId);
        notifyError(fileId, error);
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
    public List<FileAttachment> getConversationFiles(Integer conversationId) {
        return fileAttachmentDao.findByConversationId(conversationId);
    }

    /**
     * Get user's total file storage usage
     */
    public Long getUserStorageUsage(Integer userId) {
        return fileAttachmentDao.getTotalSizeByUser(userId);
    }

    // ===== CLEANUP =====
    
    public void shutdown() {
        pendingTransfers.clear();
        System.out.println("✅ FileTransferController shutdown");
    }
}