package controller;

import model.Conversation;
import model.Message;
import model.Users;
import service.ChatService;
import network.p2p.P2PManager;
import dao.UserDao;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ChatController {
    private final ChatService chatService;
    private final P2PManager p2pManager;
    private final Integer currentUserId;
    private final UserDao userDao;

    public ChatController(ChatService chatService, P2PManager p2pManager, Integer currentUserId) {
        this.chatService = chatService;
        this.p2pManager = p2pManager;
        this.currentUserId = currentUserId;
        this.userDao = new UserDao();
    }

    // ===== RESULT CLASSES =====
    public static class OperationResult {
        public boolean success;
        public String message;
        public Object data;

        public OperationResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public OperationResult(boolean success, String message, Object data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }
    }

    // ===== SEND MESSAGE (DIRECT & GROUP) =====
    /**
     * Gửi tin nhắn trong conversation (direct hoặc group)
     * 1. Lưu vào DB
     * 2. Gửi P2P tới tất cả participants
     */
    public void sendMessage(Integer conversationId, String content) {
        if (content == null || content.trim().isEmpty()) {
            System.err.println("❌ Cannot send empty message");
            return;
        }

        try {
            // 1. Validate conversation exists và user có quyền gửi
            Conversation conversation = chatService.getConversationById(conversationId);
            if (conversation == null) {
                System.err.println("❌ Conversation not found: " + conversationId);
                return;
            }

            List<Users> participants = chatService.listParticipants(conversationId);
            boolean isParticipant = participants.stream()
                .anyMatch(u -> u.getId().equals(currentUserId));

            if (!isParticipant) {
                System.err.println("❌ User not in conversation: " + currentUserId);
                return;
            }

            // 2. Lưu message vào DB
            Message savedMessage = chatService.sendMessage(
                conversationId, 
                currentUserId, 
                content.trim(), 
                null
            );

            if (savedMessage == null) {
                System.err.println("❌ Failed to save message to database");
                return;
            }

            System.out.println("✅ Message saved to DB (ID: " + savedMessage.getId() + ")");

            // 3. Gửi P2P tới các peers trong conversation
            boolean p2pSuccess = p2pManager.sendChatMessage(conversationId, content.trim());

            if (p2pSuccess) {
                System.out.println("✅ Message sent via P2P to all participants");
            } else {
                System.err.println("⚠️ Failed to send message to some P2P peers");
            }

            // 4. Reset unread count cho chính mình
            chatService.resetUnread(conversationId, currentUserId);

        } catch (Exception e) {
            System.err.println("❌ Error sending message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===== CREATE DIRECT CONVERSATION =====
    /**
     * Tạo cuộc hội thoại 1-1 với một user
     * Callback cho UI để xử lý kết quả bất đồng bộ
     */
    public void createDirectConversation(Integer friendUserId, Consumer<OperationResult> callback) {
        new Thread(() -> {
            try {
                // 1. Kiểm tra friend tồn tại
                Users friend = userDao.findById(friendUserId);
                if (friend == null) {
                    callback.accept(new OperationResult(false, "User not found"));
                    return;
                }

                // 2. Kiểm tra xem đã có conversation chưa
                Conversation existing = chatService.getDirectConversation(currentUserId, friendUserId);
                if (existing != null) {
                    callback.accept(new OperationResult(true, "Conversation already exists", existing));
                    return;
                }

                // 3. Tạo conversation mới
                Conversation newConv = chatService.createDirectConversation(currentUserId, friendUserId);
                
                if (newConv != null) {
                    System.out.println("✅ Created direct conversation: " + newConv.getId());
                    
                    // 4. Kết nối P2P với friend (nếu online)
                    connectToPeerIfOnline(friendUserId);
                    
                    callback.accept(new OperationResult(true, "Conversation created", newConv));
                } else {
                    callback.accept(new OperationResult(false, "Failed to create conversation"));
                }

            } catch (Exception e) {
                callback.accept(new OperationResult(false, "Error: " + e.getMessage()));
                e.printStackTrace();
            }
        }).start();
    }

    // ===== CREATE GROUP CONVERSATION =====
    /**
     * Tạo nhóm chat với nhiều thành viên
     * @param groupName Tên nhóm
     * @param memberUsernames Mảng username của các thành viên
     * @param callback Callback trả về kết quả
     */
    public void createGroupConversation(String groupName, String[] memberUsernames, Consumer<OperationResult> callback) {
        new Thread(() -> {
            try {
                // 1. Validate input
                if (groupName == null || groupName.trim().isEmpty()) {
                    callback.accept(new OperationResult(false, "Group name is required"));
                    return;
                }

                if (memberUsernames == null || memberUsernames.length == 0) {
                    callback.accept(new OperationResult(false, "At least one member is required"));
                    return;
                }

                // 2. Tìm tất cả member IDs
                List<Integer> memberIds = new ArrayList<>();
                memberIds.add(currentUserId); // Thêm chính mình vào nhóm

                for (String username : memberUsernames) {
                    String trimmed = username.trim();
                    if (trimmed.isEmpty()) continue;

                    Users user = userDao.findByUsername(trimmed);
                    if (user == null) {
                        callback.accept(new OperationResult(false, "User not found: " + trimmed));
                        return;
                    }
                    
                    if (!memberIds.contains(user.getId())) {
                        memberIds.add(user.getId());
                    }
                }

                if (memberIds.size() < 2) {
                    callback.accept(new OperationResult(false, "Group needs at least 2 members"));
                    return;
                }

                // 3. Tạo group conversation 
                
                Conversation group = createGroupInDatabase(groupName.trim(), memberIds);
                
                if (group != null) {
                    System.out.println("✅ Created group conversation: " + group.getId());
                    
                    // 4. Kết nối P2P với tất cả members
                    for (Integer memberId : memberIds) {
                        if (!memberId.equals(currentUserId)) {
                            connectToPeerIfOnline(memberId);
                        }
                    }
                    
                    callback.accept(new OperationResult(true, "Group created successfully", group));
                } else {
                    callback.accept(new OperationResult(false, "Failed to create group"));
                }

            } catch (Exception e) {
                callback.accept(new OperationResult(false, "Error: " + e.getMessage()));
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * Helper method để tạo group trong database
     * Bạn cần implement method này trong ConversationDao/ChatService
     */
    private Conversation createGroupInDatabase(String groupName, List<Integer> memberIds) {
        try {
            // Sử dụng Hibernate để tạo group conversation
            org.hibernate.Session session = config.HibernateUtil.getSessionFactory().openSession();
            org.hibernate.Transaction tx = session.beginTransaction();

            // Tạo Conversation entity
            Conversation conv = new Conversation();
            conv.setType(Conversation.ConversationType.group);
            conv.setName(groupName);
            conv.setCreatedBy(session.get(Users.class, currentUserId));
            conv.setCreatedAt(java.time.LocalDateTime.now());
            conv.setUpdatedAt(java.time.LocalDateTime.now());

            session.save(conv);

            // Tạo Participant cho từng member
            for (Integer memberId : memberIds) {
                model.Participant participant = new model.Participant();
                participant.setConversation(conv);
                participant.setUser(session.get(Users.class, memberId));
                participant.setJoinedAt(java.time.LocalDateTime.now());
                session.save(participant);
            }

            tx.commit();
            session.close();

            return conv;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ===== TYPING INDICATORS =====
    /**
     * Gửi typing start indicator
     */
    public void sendTypingStart(Integer conversationId) {
        try {
            p2pManager.sendTypingStart(conversationId);
        } catch (Exception e) {
            System.err.println("❌ Error sending typing start: " + e.getMessage());
        }
    }

    /**
     * Gửi typing stop indicator
     */
    public void sendTypingStop(Integer conversationId) {
        try {
            p2pManager.sendTypingStop(conversationId);
        } catch (Exception e) {
            System.err.println("❌ Error sending typing stop: " + e.getMessage());
        }
    }

    // ===== MARK MESSAGE AS SEEN =====
    /**
     * Đánh dấu message đã đọc
     */
    public void markMessageAsSeen(Integer messageId) {
        try {
            Message message = chatService.getMessageById(messageId);
            if (message == null) return;

            // Đánh dấu trong DB
            chatService.markMessageSeen(messageId, currentUserId);

            // Gửi thông báo P2P tới sender
            Integer senderId = message.getSender().getId();
            if (!senderId.equals(currentUserId)) {
                // Bạn có thể thêm method sendMessageSeen trong P2PManager nếu cần
                System.out.println("✅ Marked message " + messageId + " as seen");
            }

        } catch (Exception e) {
            System.err.println("❌ Error marking message as seen: " + e.getMessage());
        }
    }

    // ===== LOAD CONVERSATIONS =====
    /**
     * Lấy danh sách conversations của user
     */
    public List<Conversation> getConversations() {
        try {
            return chatService.listConversationsByUser(currentUserId);
        } catch (Exception e) {
            System.err.println("❌ Error loading conversations: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ===== LOAD MESSAGES =====
    /**
     * Lấy danh sách messages trong một conversation
     */
    public List<Message> getMessages(Integer conversationId) {
        try {
            List<Message> messages = chatService.listMessages(conversationId);
            
            // Reset unread count khi xem conversation
            chatService.resetUnread(conversationId, currentUserId);
            
            return messages;
        } catch (Exception e) {
            System.err.println("❌ Error loading messages: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ===== P2P CONNECTION HELPERS =====
    /**
     * Kết nối P2P với một peer nếu họ đang online
     */
    private void connectToPeerIfOnline(Integer userId) {
        try {
            network.p2p.PeerInfo peer = network.p2p.PeerDiscoveryService.getInstance().getPeer(userId);
            if (peer != null) {
                boolean connected = p2pManager.connectToPeer(userId);
                if (connected) {
                    System.out.println("✅ Connected to peer: " + userId);
                } else {
                    System.err.println("⚠️ Failed to connect to peer: " + userId);
                }
            } else {
                System.out.println("ℹ️ Peer not online: " + userId);
            }
        } catch (Exception e) {
            System.err.println("❌ Error connecting to peer: " + e.getMessage());
        }
    }

    // ===== SEARCH USERS =====
    /**
     * Tìm kiếm users theo keyword
     */
    public List<Users> searchUsers(String keyword) {
        try {
            return chatService.searchUser(keyword);
        } catch (Exception e) {
            System.err.println("❌ Error searching users: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ===== UPDATE GROUP NAME =====
    /**
     * Đổi tên nhóm (chỉ group conversation)
     */
    public void updateGroupName(Integer conversationId, String newName, Consumer<OperationResult> callback) {
        new Thread(() -> {
            try {
                boolean success = chatService.updateConversationName(conversationId, newName, currentUserId);
                
                if (success) {
                    callback.accept(new OperationResult(true, "Group name updated"));
                } else {
                    callback.accept(new OperationResult(false, "Failed to update group name"));
                }
            } catch (Exception e) {
                callback.accept(new OperationResult(false, "Error: " + e.getMessage()));
            }
        }).start();
    }

    // ===== GET CONVERSATION INFO =====
    /**
     * Lấy thông tin chi tiết conversation
     */
    public Conversation getConversationInfo(Integer conversationId) {
        try {
            return chatService.getConversationById(conversationId);
        } catch (Exception e) {
            System.err.println("❌ Error getting conversation info: " + e.getMessage());
            return null;
        }
    }

    /**
     * Lấy danh sách participants trong conversation
     */
    public List<Users> getParticipants(Integer conversationId) {
        try {
            return chatService.listParticipants(conversationId);
        } catch (Exception e) {
            System.err.println("❌ Error getting participants: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ===== CLEANUP =====
    /**
     * Cleanup khi đóng ứng dụng
     */
    public void shutdown() {
        try {
            p2pManager.shutdown();
            System.out.println("✅ ChatController shutdown complete");
        } catch (Exception e) {
            System.err.println("❌ Error during shutdown: " + e.getMessage());
        }
    }
}