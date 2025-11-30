package model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Users {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "hash_password", nullable = false, length = 255)
    private String hashPassword;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 100)
    private String email;

    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    @Column(name = "avatar_id", length = 100)
    private String avatarId;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(length = 20)
    private String phone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }


    // Relationships
    @OneToMany(mappedBy = "fromUser", cascade = CascadeType.ALL)
    private List<FriendRequest> sentRequests;

    @OneToMany(mappedBy = "toUser", cascade = CascadeType.ALL)
    private List<FriendRequest> receivedRequests;

    @OneToMany(mappedBy = "userA", cascade = CascadeType.ALL)
    private List<Friend> friendsA;

    @OneToMany(mappedBy = "userB", cascade = CascadeType.ALL)
    private List<Friend> friendsB;

    @OneToMany(mappedBy = "sender", cascade = CascadeType.ALL)
    private List<Message> messagesSent;

    @OneToMany(mappedBy = "createdBy", cascade = CascadeType.ALL)
    private List<Conversation> conversationsCreated;
}
