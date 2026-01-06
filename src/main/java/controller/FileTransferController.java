package controller;

import network.p2p.P2PManager;
import service.ChatService;
import dao.FileAttachmentDao;
import model.FileAttachment;
import model.FileAttachment.FileStatus;
import model.Message;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FileTransferController - Simplified version
 * - No ACK/NACK
 * - No checksum verification  
 * - Direct file assembly
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
        String finalFileName;
        Long fileSize;
        File sourceFile;
        String clientMessageId;
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

    public FileTransferController(P2PManager p2pManager, ChatService chatService, 
                                 Integer currentUserId, ChatController chatController) {
        this.p2pManager = p2pManager;
        this.chatService = chatService;
        this.currentUserId = currentUserId;
        this.fileAttachmentDao = new FileAttachmentDao();
        this.chatController = chatController;
        
        initializeStorageDirectories();
    }

    private void initializeStorageDirectories() {
        try {
            Files.createDirectories(Paths.get(STORAGE_BASE_DIR).toAbsolutePath());
            Files.createDirectories(Paths.get(UPLOAD_DIR).toAbsolutePath());
            Files.createDirectories(Paths.get(DOWNLOAD_DIR).toAbsolutePath());
            System.out.println("✅ Storage initialized at: " + 
                             Paths.get(STORAGE_BASE_DIR).toAbsolutePath());
        } catch (IOException e) {
            System.err.println("❌ Critical: Could not create storage: " + e.getMessage());
        }
    }

    // ===== PUBLIC API =====
    
    /**
     * Gửi file - đơn giản hóa
     */
    public void sendFile(Integer conversationId, Integer toUserId, File file) {
        if (file == null || !file.exists()) {
            notifyError(null, "File not found");
            return;
        }
        
        String fileId = UUID.randomUUID().toString();
        String clientMessageId = UUID.randomUUID().toString();
        
        FileAttachment savedAttachment;
        
        // Save to DB
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

        // Send P2P (async)
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
                fileAttachmentDao.updateStatus(
                    savedAttachment.getId(),
                    FileStatus.FAILED
                );
                notifyError(fileId, "P2P failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Hủy file transfer
     */
    public void cancelTransfer(String fileId, Integer toUserId) {
        try {
            FileTransferContext context = pendingTransfers.get(fileId);
            
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

    // ===== INCOMING FILE HANDLING =====
    
    /**
     * Nhận file chunk - đơn giản hóa
     * - Lưu chunks trực tiếp
     * - Không verify checksum
     */
    public void handleFileChunk(Integer fromUserId, String fileId, int chunkIndex,
                           byte[] chunkData, int totalChunks, String fileName,
                           Long fileSize, Integer conversationId, String clientMessageId) {
        try {
            FileTransferContext context = pendingTransfers.get(fileId);

            // Init context cho chunk đầu
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
                    context.finalFileName = determineFinalFileName(fileName);
                    pendingTransfers.put(fileId, context);
                    
                    System.out.println("📥 Receiving file: " + fileName + 
                                     " (" + formatFileSize(fileSize) + ")");
                }
            }

            // ✅ Lưu chunk vào file
            Path finalPath = Paths.get(DOWNLOAD_DIR, context.finalFileName).toAbsolutePath();
            Files.createDirectories(finalPath.getParent());

            if (chunkIndex == 0) {
                // Chunk đầu: tạo mới/ghi đè
                Files.write(
                    finalPath,
                    chunkData,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
                );
            } else {
                // Các chunk sau: append
                Files.write(
                    finalPath,
                    chunkData,
                    java.nio.file.StandardOpenOption.APPEND
                );
            }

            // ✅ Update progress
            int progress = (int) (((chunkIndex + 1) * 100.0) / totalChunks);
            notifyProgress(fileId, progress);

        } catch (Exception e) {
            System.err.println("❌ Error writing chunk " + chunkIndex + ": " + e.getMessage());
            e.printStackTrace();
            notifyError(fileId, "Write error: " + e.getMessage());
        }
    }
    
    /**
     * Xác định tên file cuối cùng - thêm timestamp nếu trùng
     */
    private String determineFinalFileName(String originalFileName) {
        Path basePath = Paths.get(DOWNLOAD_DIR, originalFileName);
        
        if (!Files.exists(basePath)) {
            return originalFileName;
        }
        
        // File exists - add timestamp
        String baseName = originalFileName;
        String extension = "";
        int lastDot = baseName.lastIndexOf('.');
        if (lastDot > 0) {
            extension = baseName.substring(lastDot);
            baseName = baseName.substring(0, lastDot);
        }
        
        String timestamp = String.valueOf(System.currentTimeMillis());
        String newFileName = baseName + "_" + timestamp + extension;
        
        System.out.println("⚠️ File exists, using: " + newFileName);
        
        return newFileName;
    }

    /**
     * Xử lý khi file transfer hoàn thành
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
                // Delay để OS flush file
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}

                Path finalPath = Paths.get(DOWNLOAD_DIR, context.finalFileName).toAbsolutePath();
                File fileObj = finalPath.toFile();

                if (!fileObj.exists()) {
                    System.err.println("❌ File completed but NOT FOUND: " + finalPath);
                    notifyError(fileId, "File missing after download");
                    return;
                }

                // ✅ Tạo message và file attachment
                Message msg = chatService.createFileMessageWithAttachment(
                    context.conversationId,
                    context.senderId,
                    fileObj,
                    fileId
                );

                if (msg == null) {
                    notifyError(fileId, "Failed to create file message");
                    return;
                }

                System.out.println("✅ File received successfully: " + context.fileName);
                notifyComplete(fileId, fileObj, false);

            } catch (Exception e) {
                notifyError(fileId, e.getMessage());
            }
        }

        pendingTransfers.remove(fileId);
        p2pManager.removeFileMetadata(fileId);
    }

    public void handleFileCanceled(String fileId, boolean isUpload) {
        FileTransferContext context = pendingTransfers.remove(fileId);
        
        if (context != null) {
            if (context.fileAttachmentId != null) {
                fileAttachmentDao.updateStatus(context.fileAttachmentId, FileStatus.CANCELED);
            }
            
            if (!isUpload) {
                // Clean up incomplete download
                try {
                    String finalFileName = context.finalFileName != null ? 
                                         context.finalFileName : context.fileName;
                    Path finalPath = Paths.get(DOWNLOAD_DIR, finalFileName);
                    if (Files.exists(finalPath)) {
                        Files.delete(finalPath);
                        System.out.println("🗑️ Deleted canceled file: " + finalPath);
                    }
                } catch (IOException e) {
                    System.err.println("⚠️ Error cleaning up canceled file: " + e.getMessage());
                }
            }
            
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
            fileAttachmentDao.updateStatus(context.fileAttachmentId, FileStatus.FAILED);
            
            if (context.messageId != null) {
                dao.MessageDao messageDao = new dao.MessageDao();
                messageDao.updateMessageStatus(context.messageId, 
                                             model.Message.MessageStatus.FAILED);
            }
        }
        
        notifyError(fileId, error);
    }

    // ===== NOTIFICATIONS =====
    
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

    // ===== PUBLIC QUERIES =====
    
    public FileAttachment getFileAttachment(String fileId) {
        return fileAttachmentDao.findByFileId(fileId);
    }

    public java.util.List<FileAttachment> getConversationFiles(Integer conversationId) {
        return fileAttachmentDao.findByConversationId(conversationId);
    }

    public Long getUserStorageUsage(Integer userId) {
        return fileAttachmentDao.getTotalSizeByUser(userId);
    }

    // ===== CLEANUP =====
    
    public void shutdown() {
        pendingTransfers.clear();
        System.out.println("✅ FileTransferController shutdown");
    }
}