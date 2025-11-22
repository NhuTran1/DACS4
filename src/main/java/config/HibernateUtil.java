package config;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.HibernateException;

/**
 * Hibernate Utility class:
 * - Build SessionFactory từ AppConfig
 * - Tạo singleton
 */
public class HibernateUtil {

    private static final SessionFactory sessionFactory;

    static {
        try {
            Configuration config = new Configuration();

            // Database connection
            config.setProperty("hibernate.connection.driver_class", "com.mysql.cj.jdbc.Driver");
            config.setProperty("hibernate.connection.url",
                    "jdbc:mysql://" + AppConfig.DB_HOST + ":" + AppConfig.DB_PORT + "/" + AppConfig.DB_NAME + "?useSSL=false&serverTimezone=UTC");
            config.setProperty("hibernate.connection.username", AppConfig.DB_USER);
            config.setProperty("hibernate.connection.password", AppConfig.DB_PASSWORD);

            // Hibernate settings
            config.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQL8Dialect");
            config.setProperty("hibernate.show_sql", "true");
            config.setProperty("hibernate.format_sql", "true");
            config.setProperty("hibernate.hbm2ddl.auto", "update");
            config.setProperty("hibernate.current_session_context_class", "thread");

            // Mapping entity classes
            config.addAnnotatedClass(model.Users.class);
            config.addAnnotatedClass(model.Friend.class);
            config.addAnnotatedClass(model.FriendRequest.class);
            config.addAnnotatedClass(model.Conversation.class);
            config.addAnnotatedClass(model.Participant.class);
            config.addAnnotatedClass(model.Message.class);
            config.addAnnotatedClass(model.MessageSeen.class);
            config.addAnnotatedClass(model.UnreadCount.class);

            // Build SessionFactory
            sessionFactory = config.buildSessionFactory();
            System.out.println("Hibernate SessionFactory created successfully (AppConfig version).");

        } catch (HibernateException ex) {
            System.err.println("Initial SessionFactory creation failed." + ex);
            throw new ExceptionInInitializerError(ex);
        }
    }

    public static SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public static void shutdown() {
        getSessionFactory().close();
        System.out.println("Hibernate SessionFactory closed.");
    }
}