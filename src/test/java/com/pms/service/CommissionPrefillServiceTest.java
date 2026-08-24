package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.CommissionRate;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CommissionRateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Commission prefill (FEATURE_2608_06 / 46): existing rate → no-op, absent + resolvable → save
 * {@code CommissionRate(COUPANG, cat, rate, isDefault=false)}, unresolved rate → no-op, non-COUPANG → no-op.
 */
@ExtendWith(MockitoExtension.class)
class CommissionPrefillServiceTest {

    @Mock private CommissionRateRepository commissionRateRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CoupangFeeResolver coupangFeeResolver;
    @InjectMocks private CommissionPrefillService service;

    @Test
    void existingRate_isNoOp() {
        given(commissionRateRepository.findByPlatformAndCategoryId("COUPANG", 5L))
                .willReturn(Optional.of(CommissionRate.builder().id(1L).build()));

        service.prefillIfAbsent(5L, "COUPANG", "가전디지털");

        verify(commissionRateRepository, never()).save(any());
    }

    @Test
    void absentAndResolvable_savesCategoryRate() {
        given(commissionRateRepository.findByPlatformAndCategoryId("COUPANG", 5L)).willReturn(Optional.empty());
        given(coupangFeeResolver.resolve("가전디지털")).willReturn(Optional.of(new BigDecimal("0.078")));
        given(categoryRepository.findById(5L)).willReturn(Optional.of(Category.builder().id(5L).name("가전").build()));

        service.prefillIfAbsent(5L, "COUPANG", "가전디지털");

        ArgumentCaptor<CommissionRate> captor = ArgumentCaptor.forClass(CommissionRate.class);
        verify(commissionRateRepository).save(captor.capture());
        CommissionRate saved = captor.getValue();
        assertThat(saved.getPlatform()).isEqualTo("COUPANG");
        assertThat(saved.getCategory().getId()).isEqualTo(5L);
        assertThat(saved.getRate()).isEqualByComparingTo("0.078");
        assertThat(saved.getIsDefault()).isFalse();
    }

    @Test
    void unresolvedRate_isNoOp() {
        given(commissionRateRepository.findByPlatformAndCategoryId("COUPANG", 5L)).willReturn(Optional.empty());
        given(coupangFeeResolver.resolve("모르는카테고리")).willReturn(Optional.empty());

        service.prefillIfAbsent(5L, "COUPANG", "모르는카테고리");

        verify(commissionRateRepository, never()).save(any());
    }

    @Test
    void nonCoupang_isNoOp() {
        service.prefillIfAbsent(5L, "NAVER", "가전디지털");

        verify(commissionRateRepository, never()).findByPlatformAndCategoryId(any(), any());
        verify(commissionRateRepository, never()).save(any());
    }
}
