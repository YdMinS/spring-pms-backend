package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

/**
 * Commission Rate entity for platform and category-specific commission rates.
 *
 * Supports two-tier fallback logic:
 * 1. Platform + category-specific rate (categoryId = specific number)
 * 2. Platform default rate (categoryId = null)
 *
 * Example:
 * - Platform: "COUPANG", categoryId: 5, rate: 0.05 (category-specific)
 * - Platform: "COUPANG", categoryId: null, rate: 0.03 (platform default)
 *
 * @see CommissionRateRepository
 * @see CommissionRateService
 */
@Entity
@Table(name = "commission_rate")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CommissionRate {

    /** Unique identifier (auto-generated) */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Platform name (e.g., "COUPANG", "GMARKET") */
    @Column(length = 50, nullable = false)
    private String platform;

    /** Category relationship for category-specific rate */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @Fetch(FetchMode.JOIN)
    private Category category;

    /** Commission rate as decimal (0.00 - 1.00), e.g., 0.05 = 5% */
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal rate;

    /** Flag indicating if this is the default commission rate for the platform */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;
}
