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
	public void sendFriendRequest(FriendRequest request) {
		Transaction tx = null;
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			tx = session.beginTransaction();
			session.save(request);
			tx.commit();
		} catch(Exception e) {
			if(tx != null) tx.rollback();
			e.printStackTrace();
		}
	}
	
	//thêm vào bảng friend (many-to-many) và xóa request.
	public void acceptFriend(FriendRequest request) {
		Transaction tx = null;
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			tx = session.beginTransaction();
			
			// 1. them hang vào bảng friend 
			String sqlInsert = "INSERT INTO friend (user_a_id, user_b_id, created_at, updated_at)"
					+ "VALUES (:userA, :userB, NOW(), NOW())";
			
			Query<?> insertQuery = session.createNativeQuery(sqlInsert);
			insertQuery.setParameter("userA", request.getFromUser().getId());
			insertQuery.setParameter("userB", request.getToUser().getId());
			insertQuery.executeUpdate();
			
			//2. Xoa friend_request
			String sqlDelete = "DELETE FROM friend_request WHERE id = :requestId";
			Query<?> deleteQuery = session.createNativeQuery(sqlDelete);
			deleteQuery.setParameter("requestId", request.getId());
			deleteQuery.executeUpdate();
			
		}
	}
	
	//từ chối: xóa request mà không tạo friend
	public void denyFriendRequest(FriendRequest request) {
		Transaction tx = null;
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			tx = session.beginTransaction();
			session.delete(request);
			tx.commit();
		} catch(Exception e) {
			if(tx != null) tx.rollback();
			e.printStackTrace();
		}
	}
	
	//liệt kê tất cả friend của user
	public List<Users> listFriend(Long userId){
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			String sql = """
					SELECT u.*
					FROM users u
					JOIN friend f ON (u.id = f.user_a_id OR u.id = u.id = f.user_b_id)
					WHERE :userId IN (f.user_a_id, f.user_b_id)
					AND u.id <> :userId
					""";
			Query<Users> query = session.createNativeQuery(sql, Users.class);
			query.setParameter("userId", userId);
			return query.getResultList();
		}
	}
	
	//liệt kê các friend request nhận đc
	public List<FriendRequest> listFriendRequest(Long userId){
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			String sql = """
					SELECT * FROM friend_request
					WHERE to_user_id id = :userId
					ORDER BY created_at DESC
					""";
			
			Query<FriendRequest> query = session.createNativeQuery(sql, FriendRequest.class);
			query.setParameter("userId", userId);
			return query.getResultList();
		}
	}
	
	//check đã tồn tại friend request từ fromUser -> toUser chưa
	public boolean exxitsFriendRequest(Long fromUserId, Long toUserId) {
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
	public boolean exitsFriend(Long userId1, Long userId2) {
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			String sql = """
					SELECT COUNT(*)
					FROM friend
					WHERE (user_a_id = :userId1 AND user_b_id = :userId2)
						OR (user_a_id = :userId2 AND user_b_id = :userId1)
					""";
			Query<?> query = session.createNativeQuery(sql);
			query.setParameter("userId", userId1);
			query.setParameter("userId2", userId2);
			Number count = (Number) query.getSingleResult();
			return count.intValue() > 0;
		}
	}
	
}
