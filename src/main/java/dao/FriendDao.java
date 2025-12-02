package dao;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import config.HibernateUtil;
import model.Friend;
import model.FriendRequest;
import model.Users;

public class FriendDao {
	//send friend request: save record into friend_request
	public void sendFriendRequest(Integer fromUserId, Integer toUserId) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();

            FriendRequest request = new FriendRequest();
            request.setFromUser(session.get(Users.class, fromUserId));
            request.setToUser(session.get(Users.class, toUserId));
            request.setMessage(null);

            session.save(request);
            tx.commit();
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            e.printStackTrace();
        }
    }
	
	
	//thêm vào bảng friend (many-to-many) và xóa request.
		public void acceptFriend(Integer requestId) {
	        Transaction tx = null;
	        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
	            tx = session.beginTransaction();

	            FriendRequest request = session.get(FriendRequest.class, requestId);
	            if (request == null) return;

	            String sqlInsert = """
	                INSERT INTO friend (user_a_id, user_b_id, created_at, updated_at)
	                VALUES (:userA, :userB, NOW(), NOW())
	            """;
	            
	            Query<?> insertQuery = session.createNativeQuery(sqlInsert);
	            insertQuery.setParameter("userA", request.getFromUser().getId());
	            insertQuery.setParameter("userB", request.getToUser().getId());
	            
	            insertQuery.executeUpdate();

	            session.delete(request); // xóa request
	            tx.commit();
	        } catch (Exception e) {
	            if (tx != null) tx.rollback();
	            e.printStackTrace();
	        }
	    }
	
	//từ chối: xóa request mà không tạo friend
	 public void denyFriendRequest(Integer requestId) {
	        Transaction tx = null;
	        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
	            tx = session.beginTransaction();
	            
	            FriendRequest request = session.get(FriendRequest.class, requestId);
	            if (request != null) {
	                session.delete(request);
	            }
	            tx.commit();
	        } catch (Exception e) {
	            if (tx != null) tx.rollback();
	            e.printStackTrace();
	        }
	 }
	
	//liệt kê tất cả friend của user
	public List<Users> listFriend(Integer userId){
	    try(Session session = HibernateUtil.getSessionFactory().openSession()){
	        String sql = """
	                SELECT u.*
	                FROM users u
	                JOIN friend f 
	                    ON (
	                        (f.user_a_id = :userId AND u.id = f.user_b_id)
	                        OR
	                        (f.user_b_id = :userId AND u.id = f.user_a_id)
	                    )
	                """;

	        Query<Users> query = session.createNativeQuery(sql, Users.class);
	        query.setParameter("userId", userId);
	        return query.getResultList();
	    }
	}

	
	//liệt kê các friend request nhận đc
	public List<FriendRequest> listFriendRequest(Integer userId){
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			String sql = """
					SELECT * FROM friend_request
					WHERE to_user_id = :userId
					ORDER BY created_at DESC
					""";
			
			Query<FriendRequest> query = session.createNativeQuery(sql, FriendRequest.class);
			query.setParameter("userId", userId);
			return query.getResultList();
		}
	}
	
	//check đã tồn tại friend request từ fromUser -> toUser chưa
	public boolean exitsFriendRequest(Integer fromUserId, Integer toUserId) {
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			String sql = """
					SELECT COUNT(*)
					FROM friend_request
					WHERE from_user_id = :fromUserId AND to_user_id = :toUserId
					""";
			Query<?> query = session.createNativeQuery(sql);
			query.setParameter("fromUserId", fromUserId);
			query.setParameter("toUserId", toUserId);
			Number count = (Number) query.getSingleResult();
			return count.intValue() > 0;
		}
	}
	
	
	//Check đã là friend hay chưa giữa 2 user
	public boolean exitsFriend(Integer userId1, Integer userId2) {
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			String sql = """
					SELECT COUNT(*)
					FROM friend
					WHERE (user_a_id = :userId1 AND user_b_id = :userId2)
						OR (user_a_id = :userId2 AND user_b_id = :userId1)
					""";
			Query<?> query = session.createNativeQuery(sql);
			query.setParameter("userId1", userId1);
			query.setParameter("userId2", userId2);
			Number count = (Number) query.getSingleResult();
			return count.intValue() > 0;
		}
	}
	
}

