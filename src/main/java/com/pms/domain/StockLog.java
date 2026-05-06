package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long stockId;

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @Column(name = "barcode_id", nullable = false)
    private Long barcodeId;

    @Column(name = "in_stock", nullable = false)
    private Integer inStock;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "stock_add", nullable = false)
    private Integer stockAdd;

    @Column(name = "stock_sub", nullable = false)
    private Integer stockSub;

    @PrePersist
    protected void onCreate() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
    }
}
