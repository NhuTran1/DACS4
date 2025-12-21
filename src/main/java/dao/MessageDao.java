package dao;

import java.util.List;

import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import config.HibernateUtil;
import model.Message;
import model.MessageSeen;
import model.Users;

public class MessageDao {

	/**
     * Tìm message theo clientMessageId (để kiểm tra idempotent)
     */
    public Message findByClientMessageId(String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isEmpty()) {
            return null;
        }
        
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = """
                SELECT *
                FROM message
                WHERE client_message_id = :clientMsgId
                LIMIT 1
                """;
            
            Query<Message> query = session.createNativeQuery(sql, Message.class);
            query.setParameter("clientMsgId", clientMessageId);
            
            List<Message> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Lưu tin nhắn mới với clientMessageId (idempotent)
     * Trả về message đã lưu hoặc message đã tồn tại
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
                return existing; // Trả về message đã tồn tại
            }
            
            // Lưu message mới
            session.save(message);
            tx.commit();
            
            System.out.println("✅ New message saved: " + message.getClientMessageId());
            return message;
            
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            e.printStackTrace();
            return null;
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
	public void markMessageSeen(Integer messageId, Integer from) {
	    Transaction tx = null;

	    try (Session session = HibernateUtil.getSessionFactory().openSession()) {
	        tx = session.beginTransaction();

	        String checkSql = """
	                SELECT COUNT(*)
	                FROM message_seen
	                WHERE message_id = :mid AND user_id = :uid
	                """;

	        Query<?> checkQuery = session.createNativeQuery(checkSql);
	        checkQuery.setParameter("mid", messageId);
	        checkQuery.setParameter("uid", from);

	        Number count = (Number) checkQuery.getSingleResult();

	        if (count.intValue() == 0) {
	            MessageSeen seen = new MessageSeen();
	            seen.setMessage(session.get(Message.class, messageId));
	            seen.setUser(session.get(Users.class, from));

	            session.save(seen); // Lưu đúng chỗ
	        }

	        tx.commit();

	    } catch (Exception e) {
	        if (tx != null) tx.rollback();
	        e.printStackTrace();
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

}
