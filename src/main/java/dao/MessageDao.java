package dao;

import java.util.List;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import config.HibernateUtil;
import model.Message;
import model.Message.MessageStatus;
import protocol.P2PMessageProtocol;
import model.MessageSeen;
import model.Users;

public class MessageDao {

    /**
     * Tìm message theo clientMessageId (để kiểm tra idempotent)
     */
	// Dùng khi ĐỘC LẬP
	public Message findByClientMessageId(String clientMessageId) {
	    try (Session session = HibernateUtil.getSessionFactory().openSession()) {
	        return findByClientMessageId(session, clientMessageId);
	    }
	}

	// Dùng khi trong transaction
	private Message findByClientMessageId(Session session, String clientMessageId) {
	    String sql = """
	        SELECT *
	        FROM message
	        WHERE client_message_id = :id
	        LIMIT 1
	    """;

	    Query<Message> q = session.createNativeQuery(sql, Message.class);
	    q.setParameter("id", clientMessageId);
	    return q.uniqueResult();
	}
    
    /**
     * Lưu tin nhắn mới với clientMessageId và status (idempotent)
     */
    public Message saveMessageIdempotent(Message message) {
        if (message.getClientMessageId() == null || message.getClientMessageId().isEmpty()) {
            throw new IllegalArgumentException("clientMessageId is required for idempotent save");
        }
        
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            
            // Kiểm tra xem message đã tồn tại chưa
            Message existing = findByClientMessageId(message.getClientMessageId());
            if (existing != null) {
                System.out.println("⚠️ Message already exists (idempotent): " + message.getClientMessageId());
                tx.rollback();
                return existing;
            }
            
            // Set default status if not set
            if (message.getStatus() == null) {
                message.setStatus(MessageStatus.PENDING);
            }
            
            // Lưu message mới
            session.save(message);
            tx.commit();
            
            System.out.println("✅ New message saved: " + message.getClientMessageId() + 
                             " (Status: " + message.getStatus() + ")");
            return message;
            
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Update message status
     */
    public boolean updateMessageStatus(Integer messageId, MessageStatus newStatus) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            
            Message message = session.get(Message.class, messageId);
            if (message != null) {
                message.setStatus(newStatus);
                session.update(message);
                tx.commit();
                
                System.out.println("✅ Message " + messageId + " status updated to: " + newStatus);
                return true;
            }
            
            tx.rollback();
            return false;
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Update message status by clientMessageId
     */
    public boolean updateMessageStatusByClientId(String clientMessageId, MessageStatus newStatus) {
        Message message = findByClientMessageId(clientMessageId);
        if (message != null) {
            return updateMessageStatus(message.getId(), newStatus);
        }
        return false;
    }
    
    /**
     * Increment retry count
     */
    public boolean incrementRetryCount(Integer messageId) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            
            String sql = """
                UPDATE message
                SET retry_count = retry_count + 1,
                    last_retry_at = NOW()
                WHERE id = :msgId
                """;
            
            Query<?> query = session.createNativeQuery(sql);
            query.setParameter("msgId", messageId);
            
            int rows = query.executeUpdate();
            tx.commit();
            
            return rows > 0;
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Get all pending messages for retry
     */
    public List<Message> getPendingMessages(Integer userId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = """
                SELECT *
                FROM message
                WHERE sender_id = :uid
                  AND status = 'PENDING'
                  AND retry_count < 3
                ORDER BY created_at ASC
                """;
            
            Query<Message> query = session.createNativeQuery(sql, Message.class);
            query.setParameter("uid", userId);
            
            return query.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }
    
    /**
     * Get failed messages that can be retried
     */
    public List<Message> getRetryableFailedMessages(Integer userId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = """
                SELECT *
                FROM message
                WHERE sender_id = :uid
                  AND status = 'FAILED'
                  AND retry_count < 3
                ORDER BY created_at ASC
                LIMIT 10
                """;
            
            Query<Message> query = session.createNativeQuery(sql, Message.class);
            query.setParameter("uid", userId);
            
            return query.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }
    
	//luu tin nhan moi
	public void saveMessage(Message message) {
		Transaction tx = null;
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			tx = session.beginTransaction();
			session.save(message);
			tx.commit();
		}catch (Exception e) { 
			if (tx != null) tx.rollback(); 
			e.printStackTrace(); 
		}
	}
	
	//Lay danh sach tin nhan theo conversation
	public List<Message> listMessageInConversation(Integer conversationId){
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			String sql = """
					SELECT *
					FROM message
					WHERE conversation_id = :cid
					ORDER BY created_at ASC
					""";
			
			Query<Message> query = session.createNativeQuery(sql,Message.class);
			query.setParameter("cid", conversationId);
			
			return query.getResultList();
		}
	}
	
	//Đánh dấu tin nhắn đã đọc cho 1 user
	public void markMessageSeen(Integer messageId, Integer userId) {
	    Transaction tx = null;
	    Session session = null;
	    
	    try {
	        // ✅ FIX: Explicit session management
	        session = HibernateUtil.getSessionFactory().openSession();
	        tx = session.beginTransaction();

	        // 1️⃣ Check existence với native query an toàn
	        String checkSql = """
	            SELECT 1
	            FROM message_seen
	            WHERE message_id = :mid AND user_id = :uid
	            LIMIT 1
	        """;

	        Object exists = session.createNativeQuery(checkSql)
	                .setParameter("mid", messageId)
	                .setParameter("uid", userId)
	                .uniqueResult();

	        if (exists == null) {
	            // 2️⃣ Chỉ insert nếu chưa tồn tại
	            MessageSeen seen = new MessageSeen();
	            
	            // ✅ Dùng getReference để tránh lazy loading issue
	            seen.setMessage(session.getReference(Message.class, messageId));
	            seen.setUser(session.getReference(Users.class, userId));
	            seen.setSeenAt(java.time.LocalDateTime.now());
	            
	            session.persist(seen);
	            
	            System.out.println("✅ Marked message " + messageId + " as seen by user " + userId);
	        } else {
	            System.out.println("⚠️ Message " + messageId + " already marked as seen by user " + userId);
	        }

	        // ✅ CRITICAL: Commit TRƯỚC KHI đóng session
	        tx.commit();
	        
	    } catch (Exception e) {
	        // ✅ FIX: Proper rollback handling
	        if (tx != null && tx.isActive()) {
	            try {
	                tx.rollback();
	                System.err.println("⚠️ Transaction rolled back");
	            } catch (Exception rollbackEx) {
	                System.err.println("⚠️ Rollback failed: " + rollbackEx.getMessage());
	            }
	        }
	        System.err.println("❌ Error marking message as seen: " + e.getMessage());
	        e.printStackTrace();
	        
	    } finally {
	        // ✅ FIX: Always close session in finally block
	        if (session != null && session.isOpen()) {
	            try {
	                session.close();
	            } catch (Exception closeEx) {
	                System.err.println("⚠️ Session close failed: " + closeEx.getMessage());
	            }
	        }
	    }
	}

	// Lấy message theo id
	public Message getMessageById(Integer messageId) {
	    try (Session session = HibernateUtil.getSessionFactory().openSession()) {
	        return session.get(Message.class, messageId);
	    } catch (Exception e) {
	        e.printStackTrace();
	        return null;
	    }
	}

	
	//Reset số tn chưa đọc trong conversation
	public void resetUnread(Integer conversationId, Integer userId) {
		Transaction tx = null;
		
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			tx = session.beginTransaction();
			
			String sql = """
				    UPDATE unread_count
				    SET count = 0
				    WHERE conversation_id = :cid AND user_id = :uid
				""";
			
			Query<?> query = session.createNativeQuery(sql);
			query.setParameter("cid", conversationId);
			query.setParameter("uid", userId);
			
			query.executeUpdate();
			tx.commit();
		} catch (Exception e) { 
			if (tx != null) tx.rollback(); 
			e.printStackTrace(); 
		}
	}
		
		// Thêm 1 method helper: tăng unread count cho tất cả user trừ sender
	public void increaseUnreadCount(Integer conversationId, Integer senderId) {
	    Transaction tx = null;
	    
	    try(Session session = HibernateUtil.getSessionFactory().openSession()) {
	        tx = session.beginTransaction();
	        
	        String sql = """
	            UPDATE unread_count
	            SET count = count + 1
	            WHERE conversation_id = :cid AND user_id <> :sid
	        """;
	        
	        Query<?> query = session.createNativeQuery(sql);
	        query.setParameter("cid", conversationId);
	        query.setParameter("sid", senderId);
	        
	        query.executeUpdate();
	        tx.commit();
	    } catch(Exception e) {
	        if(tx != null) tx.rollback();
	        e.printStackTrace();
	    }
	}
	
	// ===== THÊM VÀO MessageDao.java =====

	/**
	 * Lấy chỉ file messages trong conversation
	 */
	public List<Message> listFileMessagesInConversation(Integer conversationId) {
	    try(Session session = HibernateUtil.getSessionFactory().openSession()) {
	        String sql = """
	                SELECT *
	                FROM message
	                WHERE conversation_id = :cid
	                  AND message_type IN ('FILE', 'IMAGE', 'AUDIO')
	                ORDER BY created_at ASC
	                """;
	        
	        Query<Message> query = session.createNativeQuery(sql, Message.class);
	        query.setParameter("cid", conversationId);
	        
	        return query.getResultList();
	    } catch (Exception e) {
	        e.printStackTrace();
	        return List.of();
	    }
	}

	/**
	 * Count messages by type in conversation
	 */
	public Long countMessagesByType(Integer conversationId, String messageType) {
	    try(Session session = HibernateUtil.getSessionFactory().openSession()) {
	        String sql = """
	                SELECT COUNT(*)
	                FROM message
	                WHERE conversation_id = :cid
	                  AND message_type = :type
	                """;
	        
	        Query<Number> query = session.createNativeQuery(sql);
	        query.setParameter("cid", conversationId);
	        query.setParameter("type", messageType);
	        
	        Number result = query.uniqueResult();
	        return result != null ? result.longValue() : 0L;
	    } catch (Exception e) {
	        e.printStackTrace();
	        return 0L;
	    }
	}

	/**
	 * Get latest file messages across all conversations for a user
	 */
	public List<Message> getRecentFileMessages(Integer userId, int limit) {
	    try(Session session = HibernateUtil.getSessionFactory().openSession()) {
	        String sql = """
	                SELECT m.*
	                FROM message m
	                JOIN conversation c ON m.conversation_id = c.id
	                JOIN participant p ON c.id = p.conversation_id
	                WHERE p.user_id = :uid
	                  AND m.message_type IN ('FILE', 'IMAGE', 'AUDIO')
	                ORDER BY m.created_at DESC
	                LIMIT :limit
	                """;
	        
	        Query<Message> query = session.createNativeQuery(sql, Message.class);
	        query.setParameter("uid", userId);
	        query.setParameter("limit", limit);
	        
	        return query.getResultList();
	    } catch (Exception e) {
	        e.printStackTrace();
	        return List.of();
	    }
	}

	/**
	 * Search messages by content and type
	 */
	public List<Message> searchMessages(Integer conversationId, String keyword, String messageType) {
	    try(Session session = HibernateUtil.getSessionFactory().openSession()) {
	        StringBuilder sql = new StringBuilder("""
	                SELECT *
	                FROM message
	                WHERE conversation_id = :cid
	                  AND content LIKE :keyword
	                """);
	        
	        if (messageType != null && !messageType.isEmpty()) {
	            sql.append(" AND message_type = :type");
	        }
	        
	        sql.append(" ORDER BY created_at DESC");
	        
	        Query<Message> query = session.createNativeQuery(sql.toString(), Message.class);
	        query.setParameter("cid", conversationId);
	        query.setParameter("keyword", "%" + keyword + "%");
	        
	        if (messageType != null && !messageType.isEmpty()) {
	            query.setParameter("type", messageType);
	        }
	        
	        return query.getResultList();
	    } catch (Exception e) {
	        e.printStackTrace();
	        return List.of();
	    }
	}
	
	public Message findMessageByFileUrl(Integer conversationId, String fileUrl) {
	    try (Session session = HibernateUtil.getSessionFactory().openSession()) {
	        String sql = """
	            SELECT *
	            FROM message
	            WHERE conversation_id = :cid
	              AND image_url = :url
	            LIMIT 1
	        """;

	        Query<Message> q = session.createNativeQuery(sql, Message.class);
	        q.setParameter("cid", conversationId);
	        q.setParameter("url", fileUrl);

	        return q.uniqueResult();
	    }
	}
	
	public void update(Message message) {
	    Transaction tx = null;
	    try (Session session = HibernateUtil.getSessionFactory().openSession()) {
	        tx = session.beginTransaction();
	        session.update(message);   // hoặc session.merge(message)
	        tx.commit();
	    } catch (Exception e) {
	        if (tx != null) tx.rollback();
	        e.printStackTrace();
	    }
	}
	
	public List<Message> listMessagesWithAttachments(Integer conversationId) {
	    Session session = HibernateUtil.getSessionFactory().openSession();
	    try {
	        String sql = """
	            SELECT DISTINCT m.*
	            FROM message m
	            LEFT JOIN file_attachment fa ON fa.message_id = m.id
	            WHERE m.conversation_id = :cid
	            ORDER BY m.created_at ASC
	        """;

	        return session
	            .createNativeQuery(sql, Message.class)
	            .setParameter("cid", conversationId)
	            .getResultList();

	    } finally {
	        session.close();
	    }
	}




}