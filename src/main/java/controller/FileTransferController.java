package controller;

import network.p2p.P2PManager;
import network.p2p.FileTransferManager;
import service.ChatService;

import java.io.File;
import java.util.function.BiConsumer;

/**
 * FileTransferController
 * 
 * Trách nhiệm:
 * - Quản lý việc gửi/nhận file qua P2P
 * - Theo dõi progress
 * - Xử lý resume/cancel
 * - Lưu thông tin file vào DB
 * 
 * Phụ thuộc:
 * - P2PManager
 * - FileTransferManager
 * - ChatService (để lưu file message)
 * 
 * KHÔNG phụ thuộc UI
 */

public class FileTransferController {

	private final P2PManager p2pManager;
    private final ChatService chatService;
    private final Integer currentUserId;
    
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
    
    public FileTransferController(P2PManager p2pManager, ChatService chatService, Integer currentUserId) {
        this.p2pManager = p2pManager;
        this.chatService = chatService;
        this.currentUserId = currentUserId;
        
        setupP2PListeners();
    }
    
    /**
     * Gửi file tới user trong conversation
     */
    public void sendFile(Integer conversationId, Integer toUserId, File file) {
        if (file == null || !file.exists()) {
            notifyError(null, "File not found");
            return;
        }

        try {
            // 1. Gửi file qua P2P
            String fileId = p2pManager.sendFile(toUserId, file);
            
            // 2. Lưu file message vào DB
            String fileUrl = "file://" + file.getName() + "|" + formatFileSize(file.length());
            chatService.sendMessage(
                conversationId,
                currentUserId,
                "[File] " + file.getName(),
                fileUrl
            );
            
            System.out.println("✅ File send initiated: " + fileId);
            
        } catch (Exception e) {
            notifyError(null, "Failed to send file: " + e.getMessage());
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
           System.out.println("🚫 File transfer canceled: " + fileId);
       } catch (Exception e) {
           notifyError(fileId, "Failed to cancel transfer: " + e.getMessage());
       }
   }
   
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
   
   private String formatFileSize(Long bytes) {
       if (bytes < 1024) return bytes + " B";
       if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
       return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
   }
   
   /**
    * Called when file request is received
    */
   public void handleFileRequest(Integer fromUserId, String fileId, String fileName, Long fileSize) {
       notifyFileRequest(fromUserId, fileId, fileName, fileSize);
   }
   
   public void handleFileAccepted(Integer fromUserId, String fileId) {
       System.out.println("✅ File accepted by peer: " + fileId);
       // Progress dialog should already be shown
   }
   
   public void handleFileRejected(Integer fromUserId, String fileId, String reason) {
       notifyError(fileId, "File rejected: " + reason);
   }
   
   public void handleFileProgress(String fileId, int progress, boolean isUpload) {
       notifyProgress(fileId, progress);
   }
   
   public void handleFileComplete(String fileId, File file, boolean isUpload) {
       notifyComplete(fileId, file, isUpload);
   }
   
   public void handleFileCanceled(String fileId, boolean isUpload) {
       notifyError(fileId, "Transfer canceled");
   }
   
   public void handleFileError(String fileId, String error) {
       notifyError(fileId, error);
   }
   
   public void shutdown() {
       System.out.println("✅ FileTransferController shutdown");
   }
   
   private void setupP2PListeners() {
       // Note: P2PManager's event listener is already set in ChatController
       // We just need to hook into those events via ChatController
       // or P2PManager can support multiple listeners
   }

    
    
}
