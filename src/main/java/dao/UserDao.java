package dao;

import java.util.List;

import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import config.HibernateUtil;
import model.Users;

public class UserDao {
	
	// tìm user theo id
	public Users findById(long id) {
		try (Session session = HibernateUtil.getSessionFactory().openSession()){
			return session.get(Users.class, id);
		} catch (Exception e) {
			System.out.println("Error find user by id ");
			e.printStackTrace();
			return null;
		}
	}
	
	//search user username
	public Users findByUsername(String username) {
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			Query<Users> query = session.createQuery(
					"FROM Users WHERE username = :username", Users.class);
			query.setParameter("username", username);
			return query.uniqueResult();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
	
	//Save new user
	public void save(Users user) {
		Transaction tx = null;
		try(Session session = HibernateUtil.getSessionFactory().openSession()){
			tx = session.beginTransaction();
			session.save(user);
			tx.commit();
		} catch (Exception e) {
			if(tx != null) tx.rollback();
			e.printStackTrace();
		}
	}
	
	//update user
	public void update(Users user) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSessionFactory().openSession()){
			tx = session.beginTransaction();
			session.update(user);
			tx.commit();
		} catch (Exception e) {
			if(tx != null) tx.rollback();
			e.printStackTrace();
		}
	}
	
	//Get list friend
	public List<Users> listFriend(Long userId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {

            String sql = """
                    SELECT u.* 
                    FROM user u
                    JOIN friend f ON (u.id = f.user_a_id OR u.id = f.user_b_id)
                    WHERE :userId IN (f.user_a_id, f.user_b_id)
                    AND u.id <> :userId 
                    """;

            Query<Users> query = session.createNativeQuery(sql, Users.class);
            query.setParameter("userId", userId);

            return query.getResultList();
        }
    }
	
	
			
				
			
}
