package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "seller")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Seller extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String sellerName;

    @Column(nullable = false, unique = true, length = 50)
    private String businessRegistration;
}
