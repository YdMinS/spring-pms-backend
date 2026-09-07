package com.pms.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.OrderLine;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.CoupangMoney;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * One-off migration: fill {@code order_line} 의 금액 4컬럼 from the Coupang {@code raw} JSON
 * (FEATURE_2609_26 / PLAN D13. 구조 백필은 changeset 066 이 한다).
 *
 * <p><b>왜 러너인가</b>: 쿠팡 금액은 스칼라일 수도 protobuf Money 객체({@code {units,nanos}})일 수도 있어
 * SQL {@code JSON_EXTRACT} 로는 안전하게 못 뽑는다. 파싱은 {@link CoupangMoney} 한 곳만 알고 있어야 한다.
 *
 * <p><b>게이트</b>: {@code oclyx.migration.order-amount-backfill-enabled}(기본 {@code false}).
 * 켠 채 배포 → 실행 → 다시 끈다(FEATURE_2608_04 이미지 마이그레이션과 같은 운용).
 * ⚠️ {@code @ConditionalOnProperty} 가 아니라 <b>필드 게이트</b>인 이유: 빈이 아예 없으면 "꺼져 있을 때
 * 아무것도 하지 않는다"를 테스트로 고정할 수 없다.
 *
 * <p>🔴 <b>비-웹 컨텍스트</b>: 부팅 시점엔 SecurityContext/JWT 가 없어 {@link TenantContext} 가 비어 있다
 * → {@code @TenantId} 인 {@link OrderLine} 조회가 전부 {@code NO_TENANT(-1)} 로 필터되어 <b>0행을 읽고
 * 조용히 아무것도 하지 않는다</b>(예외도 로그도 없다). 그래서 전 테넌트를 순회하며 명시 set → finally clear
 * 한다(backend CLAUDE.md §9, {@link LocalToS3ImageMigrationRunner} 와 같은 패턴).
 * ⚠️ {@code TenantContext.set(1L)} 로 고정하지 말 것 — 백필은 전 테넌트가 대상이다.
 *
 * <p><b>멱등</b>: {@code unit_price IS NULL} 인 행만 채운다. 파싱 실패는 그 행만 건너뛰고 WARN —
 * 한 건의 형태 이상이 배치 전체를 중단시키지 않는다.
 */
@Slf4j
@Component
public class OrderAmountBackfillRunner implements ApplicationRunner {

    private static final int BATCH_SIZE = 200;

    private final OrderLineRepository orderLineRepository;
    private final CoupangOrderLineRepository coupangOrderLineRepository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public OrderAmountBackfillRunner(OrderLineRepository orderLineRepository,
                                     CoupangOrderLineRepository coupangOrderLineRepository,
                                     ObjectMapper objectMapper,
                                     @Value("${oclyx.migration.order-amount-backfill-enabled:false}") boolean enabled) {
        this.orderLineRepository = orderLineRepository;
        this.coupangOrderLineRepository = coupangOrderLineRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        Counts total = new Counts();
        List<Long> tenantIds = orderLineRepository.findDistinctTenantIds();
        log.info("[order-amount-backfill] starting for {} tenant(s)", tenantIds.size());

        for (Long tenantId : tenantIds) {
            try {
                TenantContext.set(tenantId);
                backfillOneTenant(total);
            } finally {
                // ⚠️ thread-pool reuse — a leaked tenant id is cross-tenant data exposure.
                TenantContext.clear();
            }
        }

        log.info("[order-amount-backfill] DONE — {}", total);
    }

    /**
     * 금액이 빈 라인을 id 오름차순 keyset 페이징으로 훑는다.
     *
     * <p>⚠️ 항상 첫 페이지를 다시 읽으면 파싱 실패로 계속 null 인 행이 매 회차 다시 뽑혀 무한 루프가 된다.
     */
    private void backfillOneTenant(Counts total) {
        long lastId = 0L;
        while (true) {
            List<OrderLine> batch = orderLineRepository.findAmountBackfillBatch(
                    lastId, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                return;
            }
            for (OrderLine line : batch) {
                lastId = line.getId();
                if (backfillOne(line)) {
                    total.backfilled++;
                } else {
                    total.skipped++;
                }
            }
        }
    }

    /** 라인 1건 금액 복원. 채웠으면 true, 건너뛰었으면 false. */
    private boolean backfillOne(OrderLine line) {
        try {
            Optional<CoupangOrderLine> mirror = coupangOrderLineRepository.findByOrderLine_Id(line.getId());
            if (mirror.isEmpty() || mirror.get().getRaw() == null || mirror.get().getRaw().isBlank()) {
                return false;
            }
            JsonNode item = objectMapper.readTree(mirror.get().getRaw());
            BigDecimal unitPrice = CoupangMoney.parse(item.get("salesPrice"));
            if (unitPrice == null) {
                // 단가가 없으면 이 행은 남겨 둔다 — 0 을 채우면 없는 사실을 만들어내는 것이다.
                return false;
            }
            orderLineRepository.save(line.toBuilder()
                    .unitPrice(unitPrice)
                    .lineAmount(CoupangMoney.parse(item.get("orderPrice")))
                    .discountAmount(CoupangMoney.parse(item.get("discountPrice")))
                    .platformDiscountAmount(CoupangMoney.parse(item.get("coupangDiscount")))
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("[order-amount-backfill] skipped order_line {}: {}", line.getId(), e.toString());
            return false;
        }
    }

    /** Mutable tally. */
    private static final class Counts {
        int backfilled;
        int skipped;

        @Override
        public String toString() {
            return "backfilled=" + backfilled + " skipped=" + skipped;
        }
    }
}
