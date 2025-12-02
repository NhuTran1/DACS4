package dao;

import java.time.LocalDateTime;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import config.HibernateUtil;
import model.Conversation;
import model.Conversation.ConversationType;
import model.Participant;
import model.Users;

public class ConversationDao {
	//get conversation by id
	public Conversation getConversation(Integer conversationId) {
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			return session.get(Conversation.class, conversationId);
		}catch (Exception e) {
            e.printStackTrace();
            return null;
        }
	}
	
	//get list conversation for 1 user
	public List<Conversation> listByUser(Integer userId){
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			String sql = """
					SELECT c.*
					FROM conversation c
					JOIN participant p ON c.id = p.conversation_id
					WHERE p.user_id = :uid
					ORDER BY c.updated_at DESC
					""";
			
			Query<Conversation> query = session.createNativeQuery(sql, Conversation.class);
			query.setParameter("uid", userId);
			
			return query.getResultList();
		}
	}
	
	//Check đã có direct giữa 2 user chưa
	public Conversation getDirectConversation(Integer user1, Integer user2) {
	    try (Session session = HibernateUtil.getSessionFactory().openSession()) {

	        String sql = """
	                SELECT c.*
	                FROM conversation c
	                JOIN participant p1 ON c.id = p1.conversation_id
	                JOIN participant p2 ON c.id = p2.conversation_id
	                WHERE c.type = 'direct'
	                  AND p1.user_id = :u1
	                  AND p2.user_id = :u2
	                LIMIT 1
	                """;

	        Query<Conversation> query = session.createNativeQuery(sql, Conversation.class);
	        query.setParameter("u1", user1);
	        query.setParameter("u2", user2);

	        List<Conversation> results = query.getResultList();
	        return results.isEmpty() ? null : results.get(0);
	    }
	}
	
	//create direct conversation
	public Conversation createDirectConversation(Integer userAId, Integer userBId) {
		Transaction tx = null;
		
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			tx = session.beginTransaction();
			
			// Kiểm tra xem direct conversation đã tồn tại chưa 
			Conversation exist = getDirectConversation(userAId, userBId); 
			if (exist != null) { 
				return exist; 
			}
			
			//Neu chưa, tạo mới 
			//B1: lay entity User
			Users userA= session.get(Users.class, userAId);
			Users userB = session.get(Users.class, userBId);
			
			if(userA == null || userB == null) {
				tx.rollback();
				return null;
			}
			
			//B2: Tao conversation moi
			Conversation conv = new Conversation();
			conv.setType(ConversationType.direct);
			conv.setCreatedBy(userA);
			session.save(conv);
			
			//B3: Taoj participant cho userA
			Participant p1 = new Participant();
			p1.setConversation(conv);
			p1.setUser(userA);
			p1.setJoinedAt(LocalDateTime.now());
			session.save(p1);
			
			//Tao Participant cho userB
			Participant p2 = new Participant();
			p2.setConversation(conv);
			p2.setUser(userB);
			p2.setJoinedAt(LocalDateTime.now());
			session.save(p2);
			
			tx.commit();
			return conv;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	//hàm phụ thêm participant
//	private void addParticipant(Session session, Long uid, String role) {
//		String sql = """
//				INSERT INTO participant(conversation_id, user_id, role 
//				VALUES
//				"""
//	}
	
	//5.lay danh sach paticipant
	public List<Users> listParticipants(Integer conversationId){
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			String sql = """
					SELECT u.*
					FROM users u
					JOIN participant p ON u.id = p.user_id
					WHERE p.conversation_id = :cid
					""";
			
			Query<Users> query = session.createNativeQuery(sql, Users.class);
			query.setParameter("cid", conversationId);
			
			return query.getResultList();
		}
	}
	
	//6. Update ten nhom
	public boolean updateConversation(Integer conversationId, String newName, Integer requestId) {
		Transaction tx = null;
		
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			tx = session.beginTransaction();
			
			//check quyen
			String check = """
					SELECT COUNT(*)
					FROM participant
					WHERE conversation_id = :cid AND user_id = :uid
					""";
			
			Query<?> qCheck = session.createNativeQuery(check);
			qCheck.setParameter("cid", conversationId);
			qCheck.setParameter("uid", requestId);
			
			if(((Number)qCheck.getSingleResult()).intValue() == 0) {
				return false; //ko co quyen
			}
			
			//update
			String sql = """
					UPDATE conversation 
					SET name = :name, updated_at = NOW()
					WHERE id = :cid AND type = 'group'
					""";
			
			Query<?> query = session.createNativeQuery(sql);
            query.setParameter("cid", conversationId);
            query.setParameter("name", newName);
            
            int rows = query.executeUpdate();
            tx.commit();
            
            return rows > 0;
		} catch (Exception e) {
			if (tx != null) tx.rollback();
			throw e;
		}
	}
	
	//7.Delete conversation
//	public boolean deleteConversation(Long conversationId, Long requestId) {
//		Transaction tx = null;
//		
//		try(Session session = HibernateUtil.getSessionFactory().openSession()){
//			tx = session.beginTransaction();
//			
//			//Chi creater or admin group moi xoa duoc
//			String auth = """
//					SELECT COUNT(*)
//					FROM participant 
//					WHERE conversation_id = :cid
//						AND user_id = :id
//						AND role IN ('admin', 'creater'
//					"""
//			
//			Query<?> qAuth = session.createNativeQuery(null, null, null)t
//		}
//	}
	
}

