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
	public List<Message> listMessageInConversation(Long conversationId){
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
	public void markMessageSeen(Long messageId, Long userId) {
		Transaction tx = null;
		
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			tx = session.beginTransaction();
			
			//Kiem tra co reacord chua
			String checkSql = """
					SELECT COUNT(*) 
					FROM message_seen
					WHERE message_id = :mid AND user_id = :uid
					""";
			Query<?> checkQuery = session.createNativeQuery(checkSql);
			checkQuery.setParameter("mid", messageId);
			checkQuery.setParameter("uid", userId);
			
			Number count = (Number) checkQuery.getSingleResult();
			if(count.intValue() == 0) {
				//insert moi
				MessageSeen seen = new MessageSeen();
				seen.setMessage(session.get(Message.class, messageId));
				seen.setUser(session.get(Users.class, userId));
				
				tx.commit();
				session.save(seen);
			}
		}
	}
	
	//Reset số tn chưa đọc trong conversation
	public void resetUnread(Long conversationId, Long userId) {
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
	public void increaseUnreadCount(Long conversationId, Long senderId) {
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

}
