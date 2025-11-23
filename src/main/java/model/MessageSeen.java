package model;


import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "message_seen",
       uniqueConstraints = {@UniqueConstraint(columnNames = {"message_id", "user_id"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageSeen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Column(name = "seen_at", nullable = false)
    private LocalDateTime seenAt;
    
    
}

