package dao;

import org.hibernate.Session;
import org.hibernate.Transaction;
import config.HibernateUtil;

import java.util.function.Function;

public abstract class BaseDao<T> {

    // Thực thi với transaction
    protected <R> R executeTransaction(Function<Session, R> action) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            R result = action.apply(session);
            tx.commit();
            return result;
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            e.printStackTrace();
            return null;
        }
    }

    // Thực thi không cần transaction (chỉ đọc)
    protected <R> R executeRead(Function<Session, R> action) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return action.apply(session);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}

