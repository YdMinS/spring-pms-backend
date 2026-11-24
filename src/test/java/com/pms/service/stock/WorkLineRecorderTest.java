package com.pms.service.stock;

import com.pms.domain.Product;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.dto.request.StockMovementRequest;
import com.pms.repository.ProductRepository;
import com.pms.service.PurchaseSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/** WorkLineRecorder (FEATURE_2611_34 R4) — the product-name prefix and the stock-link gate. */
@ExtendWith(MockitoExtension.class)
class WorkLineRecorderTest {

    @Mock private StockLedgerService stockLedgerService;
    @Mock private PurchaseSettingService purchaseSettingService;
    @Mock private ProductRepository productRepository;

    @InjectMocks private WorkLineRecorder workLineRecorder;

    @Test
    void testRecordPrefixesProductName() {
        given(stockLedgerService.record(any()))
                .willThrow(new IllegalArgumentException("남은 재고 수량(1개)보다 많습니다."));
        given(productRepository.findById(7L))
                .willReturn(Optional.of(Product.builder().id(7L).productName("양말A").build()));

        assertThatThrownBy(() -> workLineRecorder.record(new StockMovementRequest(7L, 3L, StockMovementType.DISPOSAL,
                2, StockReason.DAMAGED, null, null, null, null, LocalDate.of(2026, 11, 24))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("양말A: 남은 재고 수량(1개)보다 많습니다.");
    }

    @Test
    void testRequireStockLinkedRejectsWhenOff() {
        given(purchaseSettingService.isStockLinked()).willReturn(false);

        assertThatThrownBy(() -> workLineRecorder.requireStockLinked())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재고 연동이 꺼져 있습니다.");
    }
}
