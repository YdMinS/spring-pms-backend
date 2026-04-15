package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 60)
    private String password;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // Override BaseEntity column names for member table naming convention
    @Column(name = "created_date")
    private java.time.LocalDateTime createdAt;

    @Column(name = "modified_date")
    private java.time.LocalDateTime updatedAt;
}
