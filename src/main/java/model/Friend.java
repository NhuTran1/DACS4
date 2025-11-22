package model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "friend",
       uniqueConstraints = {@UniqueConstraint(columnNames = {"user_a_id","user_b_id"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Friend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "user_a_id", nullable = false)
    private Users userA;

    @ManyToOne
    @JoinColumn(name = "user_b_id", nullable = false)
    private Users userB;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
