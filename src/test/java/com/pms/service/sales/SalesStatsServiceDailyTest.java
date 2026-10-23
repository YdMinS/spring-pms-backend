package com.pms.service.sales;

import com.pms.dto.response.DailySalesResponse;
import com.pms.dto.response.DailySalesRow;
import com.pms.repository.MarketplaceAccountFixedCostRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.SettlementLineRepository;
import com.pms.repository.SettlementPayoutRepository;
import com.pms.service.MasterChannelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Sales per sale date (FEATURE_2610_29 D22) — the service part: one row per date of the period, zeros for a date
 * without sales, amounts scaled like the other sales endpoints. The SQL rules are covered by
 * {@code OrderLineDailySalesAggregationTest}.
 */
@ExtendWith(MockitoExtension.class)
class SalesStatsServiceDailyTest {

    @Mock private OrderLineRepository orderLineRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private SettlementPayoutRepository settlementPayoutRepository;
    @Mock private SettlementLineRepository settlementLineRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private MarketplaceAccountFixedCostRepository fixedCostRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;

    private SalesStatsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SalesStatsServiceImpl(orderLineRepository, productListingOptionRepository,
                settlementPayoutRepository, settlementLineRepository, marketplaceAccountRepository,
                fixedCostRepository, masterChannelConfigService, new FixedCostCalculator(), new BigDecimal("0.1"));
    }

    @Test
    void dailyHasOneRowPerDateWithZerosForDatesWithoutSales() {
        given(orderLineRepository.aggregateDailySales(
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 4, 0, 0)))
                .willReturn(List.of(new DailySalesRow(2026, 9, 2, new BigDecimal("12000"), 2L)));

        List<DailySalesResponse> rows = service.daily(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3));

        assertThat(rows).extracting(DailySalesResponse::date).containsExactly(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3));
        assertThat(rows).extracting(DailySalesResponse::orderCount).containsExactly(0L, 2L, 0L);
        assertThat(rows.get(1).grossSales()).isEqualByComparingTo("12000");
        assertThat(rows.get(0).grossSales().scale()).isEqualTo(2);
    }

    @Test
    void dailyInvertedRangeIsRejected() {
        assertThatThrownBy(() -> service.daily(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
