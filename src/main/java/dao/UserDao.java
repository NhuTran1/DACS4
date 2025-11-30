package dao;

import java.util.List;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import config.HibernateUtil;
import model.Users;

public class UserDao {

    // find by id: get information when logged in
    public Users findById(long id) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return session.get(Users.class, id);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // find user by username 
    public Users findByUsername(String username) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {

            String sql = "SELECT * FROM users WHERE username = :username";

            Query<Users> query = session.createNativeQuery(sql, Users.class);
            query.setParameter("username", username);

            return query.uniqueResult();
        }
    }

    // save new user
    public void save(Users user) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.save(user);
            tx.commit();
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            e.printStackTrace();
        }
    }

    // update user
    public void update(Users user) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            session.merge(user);
            tx.commit();
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            e.printStackTrace();
        }
    }

    // List friend 
//    public List<Users> listFriend(Long userId) {
//        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
//
//            String sql = """
//                    SELECT u.*
//                    FROM users u
//                    JOIN friend f ON (u.id = f.user_a_id OR u.id = f.user_b_id)
//                    WHERE :userId IN (f.user_a_id, f.user_b_id)
//                    AND u.id <> :userId
//                    """;
//
//            Query<Users> query = session.createNativeQuery(sql, Users.class);
//            query.setParameter("userId", userId);
//
//            return query.getResultList();
//        }
//    }

    // search user 
    public List<Users> searchUser(String keyword) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {

            String sql = """
                    SELECT * FROM users 
                    WHERE username LIKE :kw 
                    OR display_name LIKE :kw
                    """;

            Query<Users> query = session.createNativeQuery(sql, Users.class);
            query.setParameter("kw", "%" + keyword + "%");

            return query.getResultList();
        }
    }
}
