package service;

import dao.FileAttachmentDao;
import dao.MessageDao;
import model.FileAttachment;
import model.FileAttachment.FileStatus;
import model.Message;
import network.p2p.P2PManager;

import java.io.File;
import java.util.List;
import java.util.concurrent.*;

/**
 * FileRetryService - Tự động retry gửi lại failed file uploads
 */
public class FileRetryService {
    
    private final FileAttachmentDao fileAttachmentDao;
    private final MessageDao messageDao;
    private final P2PManager p2pManager;
    private final Integer userId;
    private final ChatService chatService;
    private final ScheduledExecutorService scheduler;
    
    private static final long RETRY_INTERVAL_SECONDS = 60; // 1 minute
    
    public FileRetryService(FileAttachmentDao fileAttachmentDao, MessageDao messageDao,
                           P2PManager p2pManager, Integer userId, ChatService chatService) {
        this.fileAttachmentDao = fileAttachmentDao;
        this.messageDao = messageDao;
        this.p2pManager = p2pManager;
        this.userId = userId;
        this.chatService = chatService;
        this.scheduler = Executors.newScheduledThreadPool(1);
    }
    
    /**
     * Start automatic retry service
     */
    public void start() {
        // Initial retry on startup
        retryFailedUploads();
        
        // Schedule periodic retry
        scheduler.scheduleWithFixedDelay(
            this::retryFailedUploads,
            RETRY_INTERVAL_SECONDS,
            RETRY_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        
        System.out.println("✅ File Retry Service started");
    }
    
    /**
     * Stop retry service
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        System.out.println("✅ File Retry Service shutdown");
    }
    
    /**
     * Retry all failed file uploads
     */
    public void retryFailedUploads() {
        try {
            // Get uploading files (may have been interrupted)
            List<FileAttachment> uploadingFiles = fileAttachmentDao.getUploadingFiles(userId);
            
            if (!uploadingFiles.isEmpty()) {
                System.out.println("🔄 Found " + uploadingFiles.size() + " interrupted uploads");
                
                for (FileAttachment file : uploadingFiles) {
                    retryFileUpload(file);
                }
            }
            
        } catch (Exception e) {
            System.err.println("❌ Error during file retry: " + e.getMessage());
        }
    }
    
    /**
     * Retry specific file upload
     */
    private void retryFileUpload(FileAttachment fileAttachment) {
        try {
            // Get message
            Message message = fileAttachment.getMessage();
            if (message == null) {
                System.err.println("❌ Message not found for file: " + fileAttachment.getFileId());
                fileAttachmentDao.updateStatus(fileAttachment.getId(), FileStatus.FAILED);
                return;
            }
            
            // Check if file still exists
            File sourceFile = new File(fileAttachment.getFilePath());
            if (!sourceFile.exists()) {
                System.err.println("❌ Source file not found: " + fileAttachment.getFilePath());
                fileAttachmentDao.updateStatus(fileAttachment.getId(), FileStatus.FAILED);
                return;
            }
            
            // Get conversation participants
            List<model.Users> participants = chatService.listParticipants(
                message.getConversation().getId()
            );
            
            if (participants == null || participants.isEmpty()) {
                System.err.println("❌ No participants found");
                return;
            }
            
            // Find recipient (not sender)
            Integer recipientId = participants.stream()
                .map(model.Users::getId)
                .filter(id -> !id.equals(userId))
                .findFirst()
                .orElse(null);
            
            if (recipientId == null) {
                System.err.println("❌ Recipient not found");
                return;
            }
            
            // Check if recipient is online
            if (!isPeerOnline(recipientId)) {
                System.out.println("⚠️ Recipient " + recipientId + " is offline, will retry later");
                return;
            }
            
            System.out.println("🔄 Retrying file upload: " + fileAttachment.getFileName());
            
            // Retry send via P2P
            String p2pFileId = p2pManager.sendFile(
                recipientId,
                sourceFile,
                message.getConversation().getId(),
                message.getClientMessageId()
            );
            
            System.out.println("✅ File upload retry initiated: " + fileAttachment.getFileName());
            
        } catch (Exception e) {
            System.err.println("❌ Error retrying file upload: " + e.getMessage());
            e.printStackTrace();
            
            // Mark as failed after multiple retries
            fileAttachmentDao.updateStatus(fileAttachment.getId(), FileStatus.FAILED);
        }
    }
    
    /**
     * Check if peer is online
     */
    private boolean isPeerOnline(Integer peerId) {
        return network.p2p.PeerDiscoveryService.getInstance().getPeer(peerId) != null;
    }
    
    /**
     * Manual retry for specific file
     */
    public boolean retryFileManually(String fileId) {
        FileAttachment fileAttachment = fileAttachmentDao.findByFileId(fileId);
        
        if (fileAttachment == null) {
            System.err.println("❌ File not found: " + fileId);
            return false;
        }
        
        if (!fileAttachment.getSender().getId().equals(userId)) {
            System.err.println("❌ Cannot retry file from other user");
            return false;
        }
        
        if (fileAttachment.getStatus() != FileStatus.UPLOADING && 
            fileAttachment.getStatus() != FileStatus.FAILED) {
            System.err.println("❌ File is not in retryable state: " + fileAttachment.getStatus());
            return false;
        }
        
        retryFileUpload(fileAttachment);
        return true;
    }
    
    /**
     * Clean up old failed files (optional maintenance)
     */
    public void cleanupOldFailedFiles(int daysOld) {
        try {
            // TODO: Implement cleanup of failed files older than X days
            System.out.println("🧹 Cleaning up failed files older than " + daysOld + " days");
            
        } catch (Exception e) {
            System.err.println("❌ Error during cleanup: " + e.getMessage());
        }
    }
}