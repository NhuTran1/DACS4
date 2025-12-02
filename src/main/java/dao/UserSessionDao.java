package dao;

import model.UserSession;
import org.hibernate.query.NativeQuery;

public class UserSessionDao extends BaseDao<UserSession> {

    // Lưu session
    public void save(UserSession sessionEntity) {
        executeTransaction(session -> {
            session.persist(sessionEntity);
            return null;
        });
    }

    // Tìm session theo userId và token
    public UserSession findByUserIdAndToken(Integer userId, String token) {
        return executeRead(session -> {
            NativeQuery<UserSession> query = session.createNativeQuery(
                "SELECT * FROM user_sessions WHERE user_id = :uid AND token = :token",
                UserSession.class
            );
            query.setParameter("uid", userId);
            query.setParameter("token", token);
            return query.uniqueResult();
        });
    }

    // Xóa session theo userId và token
    public void deleteByUserIdAndToken(Integer userId, String token) {
        executeTransaction(session -> {
            NativeQuery<?> query = session.createNativeQuery(
                "DELETE FROM user_sessions WHERE user_id = :uid AND token = :token"
            );
            query.setParameter("uid", userId);
            query.setParameter("token", token);
            query.executeUpdate();
            return null;
        });
    }
}
