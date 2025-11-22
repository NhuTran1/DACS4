package model;


import jakarta.persistence.*;
import lombok.*;


@Entity
@Table(name = "unread_count",
       uniqueConstraints = {@UniqueConstraint(columnNames = {"conversation_id", "user_id"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnreadCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Column(nullable = false)
    private Integer count;
}
