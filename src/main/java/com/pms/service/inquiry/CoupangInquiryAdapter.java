package com.pms.service.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 쿠팡 고객문의 동기화 (FEATURE_2609_23 / D8·D9·D10·D11·D19).
 *
 * 유형 2종(상품문의 · 고객센터문의)을 각각 돌린다. 두 API 는 경로·필수 파라미터·페이지 크기가 모두
 * 달라서 하나로 합칠 수 없다 — 합류 지점은 {@link InquiryUpserter} 다.
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 외부 HTTP 루프다. DB 쓰기는
 * {@link InquiryUpserter}(REQUIRES_NEW)·{@link InquiryStaleSweeper} 에서만 일어난다
 * ({@code CoupangClaimAdapter} 와 같은 자세).
 *
 * <p>⚠️ 한 유형이 실패해도 다른 유형은 계속 진행하고, <b>둘을 다 돌린 뒤 마지막에 예외를 다시 던진다</b>.
 * 결과만 돌려주면 파사드가 성공으로 보고 {@code lastInquirySyncAt} 을 갱신해, 실패한 유형이 놓친
 * 구간이 영구히 사라진다. 회차 격리는 파사드가 catch 로 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangInquiryAdapter implements InquirySyncAdapter {

    /** 페이징 무한루프 가드. {@code totalPages} 를 못 읽는 응답이 와도 여기서 멈춘다. */
    static final int MAX_PAGES = 20;

    /** 🔴 쿠팡 상한이다 — 두 문의 API 모두 조회 간격이 7일을 넘으면 거절한다(PLAN §4). 넓힐 수 없다. */
    static final int WINDOW_DAYS = 7;

    /** ⚠️ 교환 클레임({@code yyyy-MM-dd'T'HH:mm:ss})과 다르다 — 문의 조회는 날짜까지다. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;
    private final CoupangProductInquiryParser coupangProductInquiryParser;
    private final CoupangCallCenterInquiryParser coupangCallCenterInquiryParser;
    private final InquiryUpserter inquiryUpserter;
    private final InquiryStaleSweeper inquiryStaleSweeper;
    private final CustomerInquiryRepository customerInquiryRepository;

    @Override
    public Platform platform() {
        return Platform.COUPANG;
    }

    @Override
    public InquirySyncResult syncInquiries(MarketplaceAccount account) {
        // 스윕이 먼저다 — 종결된 건이 앵커(D8)를 뒤로 끌지 않게 한다(D9 의 존재 이유).
        int staleClosed = inquiryStaleSweeper.sweep(account);
        List<SyncWindow> slices = slices(account);

        int totalSlices = 0;
        int totalPages = 0;
        int upserted = 0;
        RuntimeException failure = null;

        for (InquiryType type : InquiryType.values()) {
            long startedAt = System.currentTimeMillis();
            int typePages = 0;
            int typeUpserted = 0;
            try {
                for (SyncWindow slice : slices) {
                    PageResult result = collect(account, type, slice);
                    typePages += result.pages();
                    typeUpserted += result.upserted();
                    totalSlices++;
                }
            } catch (RuntimeException e) {
                // 다른 유형은 계속 진행하고, 루프가 끝난 뒤 다시 던진다.
                log.warn("Coupang inquiry sync failed for type={}: account={}", type, account.getId(), e);
                failure = e;
            }
            totalPages += typePages;
            upserted += typeUpserted;
            log.info("Coupang inquiry sync: account={} type={} slices={} pages={} upserted={} elapsedMs={}",
                    account.getId(), type, slices.size(), typePages, typeUpserted,
                    System.currentTimeMillis() - startedAt);
        }

        if (failure != null) {
            throw failure;
        }
        return new InquirySyncResult(totalSlices, totalPages, upserted, staleClosed);
    }

    /**
     * 조회 슬라이스 (D8·D9·D10).
     *
     * <pre>
     * anchor = min(lastInquirySyncAt, 가장 오래된 미답변 문의의 inquiredAt)   // 둘 다 null 이면 now − 7d
     * anchor = max(anchor, now − inquiryStaleDays)                          // D9 상한
     * slices = [anchor, today] 를 7일 폭으로 분할, 최대 inquiryTrackingMaxSlices
     * </pre>
     *
     * 미답변 건을 앵커에 끌어들이므로 별도 추적 배치 없이 "우리가 아직 답 안 한 건" 이 창 안에 계속 남는다.
     * 초과분은 <b>이월하지 않는다</b>(D10) — 상한에 걸리는 것은 튜닝 신호가 아니라 STALE(D9) 미작동 신호다.
     */
    List<SyncWindow> slices(MarketplaceAccount account) {
        LocalDate today = LocalDate.now(SyncWindow.KST);
        LocalDate anchor = anchor(account, today);

        int maxSlices = coupangProperties.getInquiryTrackingMaxSlices();
        if (maxSlices <= 0) {
            return List.of();
        }

        List<SyncWindow> slices = new ArrayList<>();
        LocalDate from = anchor.isAfter(today) ? today : anchor;
        while (slices.size() < maxSlices) {
            LocalDate to = from.plusDays(WINDOW_DAYS);
            if (to.isAfter(today)) {
                to = today;
            }
            slices.add(new SyncWindow(from, to));
            if (!to.isBefore(today)) {
                return slices;
            }
            from = to.plusDays(1);
        }
        log.warn("Inquiry slices capped at {}: account={} anchor={} — this means the stale sweep (D9) is not working, "
                + "not that the cap needs tuning", maxSlices, account.getId(), anchor);
        return slices;
    }

    private LocalDate anchor(MarketplaceAccount account, LocalDate today) {
        // lastInquirySyncAt 은 서버 시각(UTC) naive 다 → UTC 로 해석한 뒤 KST 달력 날짜로 환산한다
        // (SyncWindow.recentSince 와 같은 규칙). KST 로 바로 붙이면 9시간 최근으로 오독한다.
        LocalDate lastSync = (account.getLastInquirySyncAt() == null) ? null
                : account.getLastInquirySyncAt().atZone(ZoneOffset.UTC)
                        .withZoneSameInstant(SyncWindow.KST).toLocalDate();

        // inquiredAt 은 이미 KST 벽시계다 — 다시 환산하면 9시간 밀린다.
        LocalDate oldestUnanswered = customerInquiryRepository
                .findTopByMarketplaceAccount_IdAndStatusOrderByInquiredAtAsc(
                        account.getId(), InquiryStatus.UNANSWERED)
                .map(CustomerInquiry::getInquiredAt)
                .map(LocalDateTime::toLocalDate)
                .orElse(null);

        LocalDate anchor = min(lastSync, oldestUnanswered);
        if (anchor == null) {
            anchor = today.minusDays(WINDOW_DAYS);          // 첫 실행 = 최근 7일
        }
        LocalDate floor = today.minusDays(coupangProperties.getInquiryStaleDays());
        return anchor.isBefore(floor) ? floor : anchor;     // D9 상한
    }

    private LocalDate min(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    /**
     * 한 유형 × 한 슬라이스를 페이징하며 적재한다.
     *
     * ⚠️ {@code answeredType}·{@code partnerCounselingStatus} 는 <b>필수</b>다 — 생략하면 400.
     * ⚠️ {@code pageSize} 상한이 유형마다 다르다(상품 50 / 고객센터 30). 하나로 통일하지 말 것.
     * ⚠️ 쿼리를 인코딩하지 말 것 — 서명 대상과 전송 문자열이 같아야 한다({@code CoupangApiClientImpl}).
     */
    private PageResult collect(MarketplaceAccount account, InquiryType type, SyncWindow window) {
        String path = path(type).replace("{vendorId}", account.getVendorId());
        String baseQuery = requiredFilter(type)
                + "&inquiryStartAt=" + window.from().format(DATE)
                + "&inquiryEndAt=" + window.to().format(DATE)
                + "&pageSize=" + pageSize(type);

        int pages = 0;
        int upserted = 0;
        int pageNum = 1;
        int totalPages = 1;
        while (pageNum <= totalPages && pages < MAX_PAGES) {
            JsonNode parsed = readTree(
                    coupangApiClient.get(path, baseQuery + "&pageNum=" + pageNum, account), type);
            pages++;

            for (JsonNode item : items(parsed)) {
                if (ingest(account, type, item)) {
                    upserted++;
                }
            }
            totalPages = parsed.path("data").path("pagination").path("totalPages").asInt(pageNum);
            pageNum++;
        }
        if (pages >= MAX_PAGES) {
            log.warn("Inquiry paging hit MAX_PAGES: account={} type={} window={}", account.getId(), type, window);
        }
        return new PageResult(pages, upserted);
    }

    /** 응답 목록 경로 {@code data.content[]}. 배열이 바로 오는 응답도 받아 둔다. */
    private JsonNode items(JsonNode parsed) {
        JsonNode data = parsed.path("data");
        JsonNode content = data.path("content");
        if (content.isArray()) {
            return content;
        }
        return data.isArray() ? data : parsed.path("content");
    }

    /** 한 건의 실패가 나머지 페이지를 멈추지 않도록 삼키고 로그만 남긴다. */
    private boolean ingest(MarketplaceAccount account, InquiryType type, JsonNode item) {
        try {
            InquiryRecord record = (type == InquiryType.PRODUCT_QNA)
                    ? coupangProductInquiryParser.parse(item)
                    : coupangCallCenterInquiryParser.parse(item);
            if (record == null) {
                return false;
            }
            inquiryUpserter.upsert(account, record);
            return true;
        } catch (Exception e) {
            log.warn("Inquiry ingest failed: account={} type={} inquiryId={}", account.getId(), type,
                    item.path("inquiryId").asText(), e);
            return false;
        }
    }

    private String path(InquiryType type) {
        return (type == InquiryType.PRODUCT_QNA)
                ? coupangProperties.getOnlineInquiriesPath()
                : coupangProperties.getCallCenterInquiriesPath();
    }

    private String requiredFilter(InquiryType type) {
        // D11 — 답변 완료건까지 전부 읽는다. 미답변만 조회하면 전이된 건을 다시 못 봐서 로컬이 영원히
        // 미답변으로 굳는다. 같은 창을 다시 읽는 비용은 upsert 가 멱등이라 무해하다.
        return (type == InquiryType.PRODUCT_QNA) ? "answeredType=ALL" : "partnerCounselingStatus=NONE";
    }

    private int pageSize(InquiryType type) {
        return (type == InquiryType.PRODUCT_QNA)
                ? coupangProperties.getOnlineInquiryPageSize()
                : coupangProperties.getCallCenterInquiryPageSize();
    }

    private JsonNode readTree(String json, InquiryType type) {
        try {
            return objectMapper.readTree(Optional.ofNullable(json).orElse("{}"));
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 문의 조회 응답 파싱 실패: type=" + type, e);
        }
    }

    /** 한 슬라이스의 조회 결과 — 페이지 수와 적재 건수. */
    private record PageResult(int pages, int upserted) {
    }
}
