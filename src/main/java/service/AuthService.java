package service;

import dao.UserDao;
import dao.UserSessionDao;
import model.Users;
import model.UserSession;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.security.Key;
import java.util.Date;

import org.mindrot.jbcrypt.BCrypt;


public class AuthService {
    private UserDao userDao = new UserDao();
    private UserSessionDao sessionDao = new UserSessionDao();

    // Secret key để ký JWT 
    private static final Key secretKey = Keys.secretKeyFor(SignatureAlgorithm.HS256);

    // login
    public String login(String username, String password) {
        Users user = userDao.findByUsername(username);
        if (user == null) return null;

        // check mật khẩu (hashPassword) bang BCript
        if (!BCrypt.checkpw(password, user.getHashPassword())) {
            return null;
        }

        // Tạo JWT token
        String token = generateToken(user.getId(), user.getUsername());

        // Lưu session vào DB
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setToken(token);
        session.setCreatedAt(new Date());
        // hết hạn sau 1h
        session.setExpiredAt(new Date(System.currentTimeMillis() + 3600000));
        
        sessionDao.save(session);

        return token;
    }

    // logout
    public void logout(Long userId, String token) {
        sessionDao.deleteByUserIdAndToken(userId, token);
    }

    // register
    public boolean register(String username, String displayName, String password, String email) {
        if (userDao.findByUsername(username) != null) {
            return false;
        }

        //Hash password
        String hashed = BCrypt.hashpw(password, BCrypt.gensalt());
        
        Users user = new Users();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setHashPassword(hashed);
        user.setEmail(email);

        userDao.save(user);
        return true;
    }

    // check session hợp lệ
    public boolean validateSession(String token) {
        try {
            var claims = Jwts.parserBuilder()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            Long userId = Long.valueOf(claims.getSubject());

            // kiểm tra token có tồn tại trong DB và chưa hết hạn
            UserSession session = sessionDao.findByUserIdAndToken(userId, token);
            if (session == null) return false;
            
            if (session.getExpiredAt().before(new Date())) return false;

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // tao JWT token
    private String generateToken(Long userId, String username) {

        long now = System.currentTimeMillis();

        return Jwts.builder()
                .setSubject(userId.toString())
                .claim("username", username)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + 3600000)) // expire 1h
                .signWith(secretKey)
                .compact();
    }
}
