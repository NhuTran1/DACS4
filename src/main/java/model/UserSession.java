package model;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import model.Conversation.ConversationType;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token", nullable = false, unique = true, length = 255)
    private String token;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_at", nullable = false)
    private Date createdAt;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "expired_at")
    private Date expiredAt;
    
 // Getter & Setter
//    public Integer getId() {
//        return id;
//    }
//    public void setId(Integer id) {
//        this.id = id;
//    }
//
//    public Integer getUserId() {
//        return userId;
//    }
//    public void setUserId(Integer integer) {
//        this.userId = integer;
//    }
//
//    public String getToken() {
//        return token;
//    }
//    public void setToken(String token) {
//        this.token = token;
//    }
//
//    public Date getCreatedAt() {
//        return createdAt;
//    }
//    public void setCreatedAt(Date createdAt) {
//        this.createdAt = createdAt;
//    }
//
//    public Date getExpiredAt() {
//        return expiredAt;
//    }
//    public void setExpiredAt(Date expiredAt) {
//        this.expiredAt = expiredAt;
//    }
}
