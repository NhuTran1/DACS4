package config;

/**
 * Lưu các cấu hình cơ bản của ứng dụng
 * - Server port
 * - Database name
 * - Host/port socket
 * - Các biến cấu hình khác
 */
public class AppConfig {

    // Server TCP/Socket
    public static final int SERVER_PORT = 5000;

    // Database config
    public static final String DB_NAME = "appchat";
    public static final String DB_HOST = "localhost";
    public static final int DB_PORT = 3306;
    public static final String DB_USER = "root";
    public static final String DB_PASSWORD = "";

    // Socket P2P (peer-to-peer) mặc định
    public static final String P2P_HOST = "localhost";
    public static final int P2P_PORT = 6000;

    // JWT / Token config
    public static final long JWT_EXPIRATION_MS = 24 * 60 * 60 * 1000; // 24h
    public static final String JWT_SECRET = "ChangeThisSecretKey";

    // Các biến khác có thể thêm ở đây
    public static final int MAX_CLIENTS = 50;

    private AppConfig() {
        // Private constructor để không tạo instance
    }
}