package com.pms.service.price;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.PriceChangeReason;
import com.pms.dto.request.PriceOverrideRequest;
import com.pms.dto.response.PriceOverrideResult;
import com.pms.dto.response.RecalculateResult;
import com.pms.dto.response.RepricePushResult;
import com.pms.dto.response.RepricingCandidatesResponse;
import com.pms.dto.response.RepricingCandidatesResponse.Exclusion;
import com.pms.dto.response.RepricingCandidatesResponse.Group;
import com.pms.dto.response.RepricingCandidatesResponse.Row;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import com.pms.service.PriceCalculator;
import com.pms.service.listing.ListingChannel;
import com.pms.service.listing.ListingChannelResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 마진 경보 조회 구현(FEATURE_2609_39 / 01). 저장·전송 없음.
 *
 * <p><b>쿼리 예산</b>(D16) — 옵션 수가 아니라 <b>셀 수</b>에 비례해야 한다:</p>
 * <ol>
 *   <li>대상 셀 1쿼리({@code findRepricingTargets} — SELLING·쿠팡·판매자 필터가 전부 쿼리 안에 있다)</li>
 *   <li>옵션 1쿼리 + 동반 로드 1쿼리({@code findWithConfigByIdIn} 의 {@code @EntityGraph})</li>
 *   <li>BOM 1쿼리({@code findWithProductByOptionIdIn} — product 까지 fetch)</li>
 *   <li>수수료·마진 프리셋 해석은 <b>셀당 1회</b>, 택배·박스 해석은 (셀, 마스터옵션)당 1회 — 캐시</li>
 * </ol>
 *
 * <p>🔴 한 행이 죽어도 요청 전체가 죽으면 안 된다: 수수료 미설정 셀 하나 때문에 판매자 전체 목록이 400 이 되면
 * 정작 고쳐야 할 나머지를 볼 수 없다. 계산 실패는 그 행만 {@code UNCALCULABLE} 로 내려보낸다.</p>
 *
 * <p>⚠️ 테넌트 스코프는 <b>셀 쿼리에서 시작</b>한다(D24) — {@code ProductListingOption} 에는 {@code @TenantId}
 * 가 없어 id 기반 조회가 테넌트 필터를 타지 않는다. 옵션 id 는 반드시 이 테넌트의 셀에서 얻은 것만 쓴다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepricingServiceImpl implements RepricingService {

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final PriceCalculator priceCalculator;
    // 02(실행)이 쓰는 것들. 🔴 ListingOptionService 는 여기 없다 — setOptionPrices 재사용 금지가 D8 이다.
    private final ListingAssetService listingAssetService;
    private final ListingChannelResolver channelResolver;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    /** 가격 이력을 쓰는 유일한 창구(2609_28 D23). 직접 입력도 예외가 아니다 — 사람이 친 값이야말로 근거가 남아야 한다. */
    private final PriceHistoryRecorder priceHistoryRecorder;

    /**
     * 자기 자신의 프록시. {@code recalculateOne}/{@code pushOne} 을 이걸로 불러야 {@code REQUIRES_NEW} 광고가
     * 실제로 걸린다 — 직접(자기) 호출은 프록시를 거치지 않아 경계가 열리지 않는다.
     */
    @Autowired
    @Lazy
    private RepricingService self;

    @Override
    @Transactional(readOnly = true)
    public RepricingCandidatesResponse candidates(Long sellerId, Platform platform, Scope scope) {
        Scope effectiveScope = scope == null ? Scope.BELOW : scope;

        List<ProductListing> cells = productListingRepository.findRepricingTargets(sellerId, platform);
        if (cells.isEmpty()) {
            return new RepricingCandidatesResponse(List.of(), List.of());
        }

        List<ProductListingOption> options = loadOptions(cells);
        Map<Long, BigDecimal> costSums = costSums(options);

        // Per-cell and per-(cell, master option) resolution caches — the whole point of D16.
        Map<Long, CellBasis> cellBasisCache = new HashMap<>();
        Map<String, OptionBasis> optionBasisCache = new HashMap<>();

        Map<String, Accumulator> accumulators = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        for (ProductListingOption option : options) {
            ProductListing cell = option.getProductListing();
            CellBasis cellBasis = cellBasisCache.computeIfAbsent(cell.getId(), id -> resolveCellBasis(cell));
            Row row = row(cell, option, cellBasis, costSums.getOrDefault(option.getId(), BigDecimal.ZERO),
                    optionBasisCache);
            rows.add(row);
            accumulators
                    .computeIfAbsent(groupKey(cell), key -> new Accumulator(cell.getSeller().getId(),
                            cell.getSeller().getSellerName(), cell.getPlatform().name()))
                    .add(row, cellBasis.basis());
        }

        List<Group> groups = accumulators.values().stream()
                .map(Accumulator::toGroup)
                .sorted(Comparator.comparing(Group::sellerName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Group::platform))
                .toList();

        // scope 는 행만 거른다 — 「대응 필요인데 실행할 수 없는 행」을 숨기면 D23 이 무의미해지므로 below 가
        // 기준이고 excluded 는 기준이 아니다.
        List<Row> visible = effectiveScope == Scope.ALL ? rows : rows.stream().filter(Row::below).toList();
        return new RepricingCandidatesResponse(groups, visible);
    }

    // ---------------------------------------------------------------- ① 재계산 (로컬 전용)

    /**
     * 🔴 <b>부모 트랜잭션을 열지 않는다.</b> 클래스에 {@code @Transactional} 이 없고 이 메서드에도 붙이지
     * 않는다 — 셀마다 {@code REQUIRES_NEW} 가 도는데 부모가 열려 있으면 셀 하나의 실패가 부모를
     * rollback-only 로 만들어 「하나가 실패해도 나머지는 커밋된다」는 계약이 깨진다
     * ({@code MasterPropagationServiceImpl} 과 같은 자세).
     *
     * <p>🔴 마켓 호출 0회. 이 경로는 네트워크를 쓰지 않는다.</p>
     */
    @Override
    public RecalculateResult recalculate(List<Long> listingIds) {
        // 스코프 검증을 먼저 끝낸다: 남의 테넌트 id 가 섞여 있으면 한 셀도 건드리기 전에 404 로 세운다(D24).
        List<ProductListing> cells = new ArrayList<>();
        for (Long listingId : listingIds) {
            cells.add(productListingRepository.findScopedById(listingId)
                    .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId)));
        }

        int optionChanged = 0;
        List<RecalculateResult.FailedCell> failed = new ArrayList<>();
        for (ProductListing cell : cells) {
            try {
                optionChanged += self.recalculateOne(cell);   // 프록시 → REQUIRES_NEW: 셀마다 독립 커밋
            } catch (Exception e) {
                // 셀 하나가 실패해도 나머지는 계속한다 — 이미 커밋된 셀을 되돌리지 않는다.
                log.warn("[REPRICE-RECALC] cellId={} recalculate failed: {}", cell.getId(), e.getMessage());
                failed.add(new RecalculateResult.FailedCell(cell.getId(), e.getMessage()));
            }
        }

        String status;
        if (!cells.isEmpty() && failed.size() == cells.size()) {
            status = "FAILED";
        } else if (!failed.isEmpty()) {
            status = "PARTIAL";
        } else {
            status = "SUCCESS";
        }
        return new RecalculateResult(status, cells.size(), optionChanged, failed);
    }

    /**
     * 셀 1건 재계산. ♻️ 공식도 이력도 새로 쓰지 않는다 — 기존 이음매
     * {@link ListingAssetService#recalculateOptionPrices} 를 그대로 부른다. {@code MANUAL_OVERRIDE} 건너뛰기
     * (D6)와 {@code PriceChangeLog}(D14)가 이미 그 안에 있다.
     *
     * <p>🔴 {@code needsMarketSync} 를 켜지 않는다(D9). 그래서 {@code MasterPropagationService.propagateOne}
     * 을 재사용하지 않는다 — 그 메서드는 켠다.</p>
     *
     * <p>⚠️ 인자로 받은 셀은 바깥 루프가 읽은 것이라 이 트랜잭션에서는 detached 다. LAZY 연관(카테고리·배송·
     * 박스·마스터)을 타는 재계산을 그대로 돌리면 터지므로, 이 경계 안에서 <b>id 로 다시 읽어</b> 넘긴다.</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recalculateOne(ProductListing cell) {
        ProductListing managed = productListingRepository.findScopedById(cell.getId())
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", cell.getId()));

        // 변경 건수는 판매가 스냅샷의 차이로 센다(불변 BigDecimal 이라 재계산이 덮어써도 값은 남는다).
        Map<Long, BigDecimal> before = new HashMap<>();
        productListingOptionRepository.findByProductListingId(managed.getId())
                .forEach(option -> before.put(option.getId(), option.getSellingPrice()));

        listingAssetService.recalculateOptionPrices(managed);

        int changed = 0;
        for (ProductListingOption option : productListingOptionRepository.findByProductListingId(managed.getId())) {
            if (!sameAmount(before.get(option.getId()), option.getSellingPrice())) {
                changed++;
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- ② 마켓 반영 (전송)

    /**
     * 🔴 이 메서드에도 {@code @Transactional} 을 붙이지 않는다 — 옵션마다 「전송 + 저장」이 독립 커밋이어야
     * 하기 때문이다(D7). 하나로 묶으면 20번째에서 터졌을 때 이미 마켓에 나간 19건의 기록이 사라진다.
     *
     * <p>🔴 {@code priceSource} 를 건드리지 않는다(D8) — {@code setOptionPrices} 를 쓰지 않는 이유 전부가
     * 이것이다. 계산된 가격을 그 경로로 밀면 그 옵션이 {@code MANUAL_OVERRIDE} 가 되어 다음 재계산부터
     * 영구 제외된다.</p>
     */
    @Override
    public RepricePushResult push(List<Long> optionIds) {
        Map<Long, ProductListingOption> byId = loadScopedOptions(optionIds);

        // (sellerId, platform) 당 1회만 해석한다 — 한 요청이 판매자 하나를 다뤄도 셀은 여럿이다.
        Map<String, AccountResolution> accountCache = new HashMap<>();
        int pushed = 0;
        List<RepricePushResult.SkippedOption> skipped = new ArrayList<>();
        List<RepricePushResult.FailedOption> failed = new ArrayList<>();
        boolean stopped = false;
        Instant retryAfter = null;

        for (Long optionId : optionIds) {
            ProductListingOption option = byId.get(optionId);
            ProductListing cell = option.getProductListing();

            // 🔴 화면이 걸렀다고 믿지 않는다: 요청은 id 로 오므로 제외 규칙을 서버가 다시 판정한다.
            String skipReason = skipReason(cell, option, Purpose.PUSH);
            if (skipReason != null) {
                skipped.add(new RepricePushResult.SkippedOption(optionId, option.getOptionName(), skipReason));
                continue;
            }
            Optional<ListingChannel> channel = channelResolver.resolveOptional(cell.getPlatform());
            if (channel.isEmpty()) {
                skipped.add(new RepricePushResult.SkippedOption(optionId, option.getOptionName(),
                        "가격 전송을 지원하지 않는 채널"));
                continue;
            }
            AccountResolution account = accountCache.computeIfAbsent(
                    cell.getSeller().getId() + "|" + cell.getPlatform().name(), key -> resolveAccount(cell));
            if (account.error() != null) {
                // D22: 계정 하나 때문에 요청 전체를 세우면 「옵션마다 개별 커밋」 계약이 무의미해진다.
                failed.add(new RepricePushResult.FailedOption(optionId, option.getOptionName(),
                        account.error()));
                continue;
            }

            try {
                self.pushOne(channel.get(), option, account.account());   // 프록시 → REQUIRES_NEW
                pushed++;
            } catch (CoupangRateLimitedException e) {
                // 🔴 쿨다운은 프로세스 전역이고 약 10분이다 — 남은 옵션을 계속 치면 차단만 길어진다.
                //    여기까지의 결과는 이미 마켓에 나갔으므로 429 로 세우지 않고 200 + stopped 로 돌려준다.
                log.warn("[REPRICE-PUSH] rate limited, stopping after {} pushed", pushed);
                stopped = true;
                retryAfter = e.getRetryAfter();
                break;
            } catch (Exception e) {
                // 거부된 옵션은 저장하지 않는다 — market_price 를 갱신하면 다음 조회에서 「밀렸다」고 거짓말한다.
                log.warn("[REPRICE-PUSH] optionId={} push failed: {}", optionId, e.getMessage());
                failed.add(new RepricePushResult.FailedOption(optionId, option.getOptionName(), e.getMessage()));
            }
        }
        return new RepricePushResult(pushed, skipped, failed, stopped, retryAfter);
    }

    /**
     * 옵션 1건: 마켓에 보내고, <b>마켓이 받아들인 그 값만</b> {@code market_price} 에 적는다.
     *
     * <p>⚠️ 보내는 값은 {@code sellingPrice} 그대로다(정규화하지 않는다). 저장하는 값도 같은 객체라
     * {@code market_price == selling_price} 가 정확히 성립하고, 그래야 다음 조회의 「아직 안 밀림」 판정
     * (D15)이 어긋나지 않는다.</p>
     *
     * <p>🔴 {@code toBuilder()} 는 {@code priceSource} 를 그대로 복사한다 — 이 경로는 그 칸을 절대 쓰지 않는다(D8).</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void pushOne(ListingChannel channel, ProductListingOption option, MarketplaceAccount account) {
        BigDecimal price = option.getSellingPrice();
        if (price == null) {
            throw new IllegalStateException("판매가가 없습니다");
        }
        channel.updateOptionPrice(option, price, account);
        productListingOptionRepository.save(option.toBuilder()
                .marketPrice(price)
                .marketPriceAt(LocalDateTime.now())
                .build());
    }

    // ---------------------------------------------------------------- 직접 입력 (로컬 전용, 2609_42)

    /**
     * 사람이 친 판매가를 그대로 로컬에 적는다. <b>마켓 호출 0회</b>(2609_42 D1).
     *
     * <p>🔴 이 메서드에도 {@code @Transactional} 을 붙이지 않는다 — 옵션마다 독립 커밋이어야 20번째 실패가
     * 이미 저장된 19건을 되돌리지 않는다({@code push} 와 같은 계약).</p>
     *
     * <p>🔴 채널·계정을 <b>해석하지 않는다</b>: 마켓에 나가지 않으므로 필요 없고, 부르면 어댑터가 없는 플랫폼의
     * 옵션이 이유 없이 막힌다.</p>
     */
    @Override
    public PriceOverrideResult override(List<PriceOverrideRequest.Item> items) {
        List<Long> optionIds = items.stream().map(PriceOverrideRequest.Item::optionId).toList();
        Map<Long, ProductListingOption> byId = loadScopedOptions(optionIds);   // D24: 스코프 밖이면 404

        int applied = 0;
        List<PriceOverrideResult.SkippedOption> skipped = new ArrayList<>();
        List<PriceOverrideResult.FailedOption> failed = new ArrayList<>();

        for (PriceOverrideRequest.Item item : items) {
            ProductListingOption option = byId.get(item.optionId());
            ProductListing cell = option.getProductListing();

            // 🔴 화면이 걸렀다고 믿지 않는다: 요청은 id 로 오므로 제외 규칙을 서버가 다시 판정한다.
            String skipReason = skipReason(cell, option, Purpose.OVERRIDE);
            if (skipReason != null) {
                skipped.add(new PriceOverrideResult.SkippedOption(item.optionId(), option.getOptionName(),
                        skipReason));
                continue;
            }
            try {
                self.overrideOne(option, item.price());   // 프록시 → REQUIRES_NEW: 옵션마다 독립 커밋
                applied++;
            } catch (Exception e) {
                log.warn("[REPRICE-OVERRIDE] optionId={} save failed: {}", item.optionId(), e.getMessage());
                failed.add(new PriceOverrideResult.FailedOption(item.optionId(), option.getOptionName(),
                        e.getMessage()));
            }
        }
        return new PriceOverrideResult(applied, skipped, failed);
    }

    /**
     * 옵션 1건: 입력값을 {@code selling_price} 에 적고 이력을 남긴다.
     *
     * <p>🔴 {@code toBuilder()} 가 {@code priceSource} 를 그대로 복사한다(D2) — 이 경로는 그 칸을 절대 쓰지
     * 않는다. 쓰는 순간 그 옵션이 {@code MANUAL_OVERRIDE} 가 되어 다음 재계산부터 영구 제외되고, 「이번
     * 한 번만」이라는 이 기능의 정의가 뒤집힌다.</p>
     *
     * <p>🔴 {@code marketPrice}·{@code marketPriceAt} 도 건드리지 않는다(D10) — 그대로 둬야 다음 조회에서
     * 「아직 안 밀림」이 켜져 사람이 [마켓 반영]을 눌러야 한다는 사실이 화면에 드러난다.</p>
     *
     * <p>⚠️ {@code originalPrice}(할인 전 표시가)도 이 경로가 만지지 않는다 — 그 칸은 공식 재계산이 소유한다.</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void overrideOne(ProductListingOption option, BigDecimal price) {
        BigDecimal oldPrice = option.getSellingPrice();
        productListingOptionRepository.save(option.toBuilder()
                .sellingPrice(price)
                .build());
        // D5: 사유는 기존 MANUAL 재사용 — 두 경로 모두 답은 "사람이 직접 넣었다"다. DRAFT 셀 걸러내기는
        // recorder 가 스스로 한다.
        priceHistoryRecorder.recordSellingPrice(option, oldPrice, price, PriceChangeReason.MANUAL);
    }

    /**
     * 요청 옵션 id 를 <b>부모 셀을 통해</b> 스코프 검증하며 읽는다(D24).
     *
     * <p>🔴 {@code findWithConfigByIdIn} 결과를 그대로 믿으면 안 된다 — {@code ProductListingOption} 에는
     * {@code @TenantId} 가 없어 <b>다른 테넌트의 옵션도 읽힌다.</b> 셀 id 를 테넌트 필터가 걸린
     * {@code findScopedById} 로 다시 확인한다.</p>
     *
     * <p>⚠️ 스코프 밖이 하나라도 있으면 부분 성공으로 섞지 않고 404 로 세운다 — 섞으면 남의 데이터가
     * 존재하는지를 알려주는 셈이다.</p>
     */
    private Map<Long, ProductListingOption> loadScopedOptions(List<Long> optionIds) {
        Map<Long, ProductListingOption> byId = productListingOptionRepository.findWithConfigByIdIn(optionIds)
                .stream()
                .collect(Collectors.toMap(ProductListingOption::getId, option -> option, (first, dup) -> first));
        for (Long optionId : optionIds) {
            if (!byId.containsKey(optionId)) {
                throw new ResourceNotFoundException("ProductListingOption", optionId);
            }
        }
        Set<Long> cellIds = byId.values().stream()
                .map(option -> option.getProductListing().getId())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        for (Long cellId : cellIds) {
            if (productListingRepository.findScopedById(cellId).isEmpty()) {
                throw new ResourceNotFoundException("ProductListing", cellId);
            }
        }
        return byId;
    }

    /**
     * 대상이 아닌 사유. null = 처리한다. 순서는 {@code PUSH} 기준으로 「채널 → 셀 상태 → 식별자 → 가격
     * 소유자」이고, {@code OVERRIDE} 는 그 중 <b>전송에만 필요한 두 가지</b>를 건너뛴다.
     *
     * <p>🔴 채널 지원 여부와 마켓 식별자는 <b>전송 전용</b> 조건이다(2609_42): 아직 등록 전인 셀이나 어댑터가
     * 없는 플랫폼의 셀도 <b>로컬 판매가는 정할 수 있다</b>. 직접 입력에서 이 둘로 막으면 이유 없이 막는 것이다.</p>
     *
     * <p>⚠️ 분기는 {@code purpose} 하나로만 갈린다 — 판정 <b>순서</b>는 두 경로가 같아서 {@code push} 가
     * 돌려주던 사유 문장이 한 글자도 달라지지 않는다.</p>
     */
    private static String skipReason(ProductListing cell, ProductListingOption option, Purpose purpose) {
        if (purpose == Purpose.PUSH && cell.getPlatform() != Platform.COUPANG) {
            return "가격 전송을 지원하지 않는 채널";   // D17
        }
        if (cell.getStatus() != ListingStatus.SELLING) {
            return "판매중인 상품이 아님";              // D20 — 팔지 않는 상품의 가격을 바꾸지 않는다
        }
        if (purpose == Purpose.PUSH && option.getPlatformOptionId() == null) {
            return "마켓 옵션 식별자 없음";
        }
        if (option.getPriceSource() == GeneratedContentSource.MANUAL_OVERRIDE) {
            return "직접 지정한 가격";                  // D6·D23 — 해제는 [기본값으로 변경] 경로가 소유한다
        }
        return null;
    }

    /** 제외 규칙을 쓰는 두 경로. 전송({@code PUSH})만 채널·마켓 식별자를 따진다. */
    private enum Purpose {
        /** ② 마켓 반영 — 실제로 마켓에 나간다. */
        PUSH,
        /** 판매가 직접 입력(2609_42) — 로컬 저장뿐이다. */
        OVERRIDE
    }

    /**
     * 셀의 (판매자, 채널) 계정. ♻️ 규칙은 {@code ListingOptionServiceImpl.resolveAccount} 와 같지만
     * <b>던지지 않고</b> 사유를 돌려준다 — 여기서 예외가 나가면 계정 하나 때문에 요청 전체가 죽는다(D22).
     */
    private AccountResolution resolveAccount(ProductListing cell) {
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElse(null);
        if (account == null) {
            return new AccountResolution(null, "판매채널 계정이 없습니다");
        }
        if (Boolean.FALSE.equals(account.getIsActive())) {
            return new AccountResolution(null, "비활성 계정");
        }
        return new AccountResolution(account, null);
    }

    /** 소수 자릿수가 달라도 같은 금액이면 변경이 아니다(10000 vs 10000.00). */
    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    /** 계정 해석 결과 또는 그 실패 사유. 실패도 캐시한다 — 같은 판매자에서 같은 조회를 반복하지 않는다. */
    private record AccountResolution(MarketplaceAccount account, String error) {
    }

    // ---------------------------------------------------------------- loading

    /**
     * 대상 옵션 로드. 제외 규칙 중 <b>마켓 식별자 없음</b>을 여기서 거른다(셀 상태·플랫폼은 쿼리가 이미 걸렀다).
     * 동반 로드는 기존 {@code findWithConfigByIdIn} 을 그대로 쓴다 — 셀·마스터·마스터옵션이 한 쿼리에 딸려온다.
     */
    private List<ProductListingOption> loadOptions(List<ProductListing> cells) {
        List<Long> cellIds = cells.stream().map(ProductListing::getId).toList();
        List<Long> optionIds = productListingOptionRepository.findByProductListingIdIn(cellIds).stream()
                .filter(option -> option.getPlatformOptionId() != null)
                .map(ProductListingOption::getId)
                .toList();
        if (optionIds.isEmpty()) {
            return List.of();
        }
        return productListingOptionRepository.findWithConfigByIdIn(optionIds).stream()
                .sorted(Comparator.comparing(ProductListingOption::getId))
                .toList();
    }

    /** Σ(product.price × quantity) per option, from ONE BOM query (null price = 0). */
    private Map<Long, BigDecimal> costSums(List<ProductListingOption> options) {
        if (options.isEmpty()) {
            return Map.of();
        }
        Set<Long> optionIds = options.stream().map(ProductListingOption::getId).collect(Collectors.toSet());
        Map<Long, BigDecimal> sums = new HashMap<>();
        for (ProductListingProduct line : productListingProductRepository.findWithProductByOptionIdIn(optionIds)) {
            BigDecimal price = line.getProduct().getPrice();
            if (price == null) {
                continue;
            }
            sums.merge(line.getProductListingOption().getId(),
                    price.multiply(BigDecimal.valueOf(line.getQuantity())), BigDecimal::add);
        }
        return sums;
    }

    // ---------------------------------------------------------------- per row

    private Row row(ProductListing cell, ProductListingOption option, CellBasis cellBasis,
                    BigDecimal costSum, Map<String, OptionBasis> optionBasisCache) {
        BigDecimal marketPrice = option.getMarketPrice();
        BigDecimal sellingPrice = option.getSellingPrice();
        BigDecimal judgedPrice = marketPrice != null ? marketPrice : sellingPrice;
        // D15: NULL 은 「아직 안 밀림」이 아니라 「알 수 없음」이다. NULL 을 안 밀린 것으로 세면 새로 등록·편입한
        // 상품이 전부 여기 쌓인다.
        boolean pendingPush = marketPrice != null && sellingPrice != null
                && marketPrice.compareTo(sellingPrice) != 0;
        boolean manual = option.getPriceSource() == GeneratedContentSource.MANUAL_OVERRIDE;

        if (cellBasis.error() != null) {
            return uncalculable(cell, option, judgedPrice, marketPrice, sellingPrice, pendingPush,
                    cellBasis.error());
        }
        OptionBasis optionBasis = optionBasisCache.computeIfAbsent(
                cell.getId() + "|" + masterOptionId(option),
                key -> resolveOptionBasis(cellBasis.basis(), cell, option.getMasterProductOption()));
        if (optionBasis.error() != null) {
            return uncalculable(cell, option, judgedPrice, marketPrice, sellingPrice, pendingPush,
                    optionBasis.error());
        }

        PriceCalculator.CostBreakdown breakdown;
        BigDecimal newPrice;
        try {
            breakdown = priceCalculator.breakdown(optionBasis.basis(), costSum, judgedPrice);
            newPrice = priceCalculator.prices(optionBasis.basis(), costSum).salePrice();
        } catch (RuntimeException e) {
            return uncalculable(cell, option, judgedPrice, marketPrice, sellingPrice, pendingPush,
                    e.getMessage());
        }

        boolean below = below(cellBasis.basis(), breakdown);
        return new Row(cell.getId(), cell.getName(), option.getId(), option.getOptionName(),
                cell.getSeller().getId(), cell.getPlatform().name(),
                judgedPrice, breakdown.costSum(), breakdown.delivery(), breakdown.box(),
                breakdown.feeAmount(), breakdown.marginAmount(), breakdown.marginRate(),
                newPrice, marketPrice, sellingPrice, pendingPush, below,
                manual ? Exclusion.MANUAL : null,
                manual ? "직접 지정한 가격" : null);
    }

    /**
     * 대응 필요 판정(D4): 기준값이 <b>하나라도</b> 밑돌면 true. NULL 기준 = 그 조건 미사용이므로 두 기준이 다
     * 비어 있으면 마진이 음수여도 경보하지 않는다(지어낸 기본선으로 전 상품을 대응 필요로 만들지 않는다).
     */
    private static boolean below(PriceCalculator.CellPricingBasis basis, PriceCalculator.CostBreakdown breakdown) {
        BigDecimal minAmount = basis.minMarginAmount();
        BigDecimal minRate = basis.minMarginRate();
        return (minAmount != null && breakdown.marginAmount().compareTo(minAmount) < 0)
                || (minRate != null && breakdown.marginRate().compareTo(minRate) < 0);
    }

    /**
     * 계산 불가 행. 🔴 {@code below = false} — 마진을 못 냈는데 「기준 미달」이라고 말할 수는 없다. 직접 지정가
     * 이면서 계산까지 불가한 행은 더 강한 쪽인 {@code UNCALCULABLE} 로 표시한다(어차피 실행 대상이 아니다).
     */
    private static Row uncalculable(ProductListing cell, ProductListingOption option, BigDecimal judgedPrice,
                                    BigDecimal marketPrice, BigDecimal sellingPrice, boolean pendingPush,
                                    String reason) {
        return new Row(cell.getId(), cell.getName(), option.getId(), option.getOptionName(),
                cell.getSeller().getId(), cell.getPlatform().name(),
                judgedPrice, null, null, null, null, null, null,
                null, marketPrice, sellingPrice, pendingPush, false,
                Exclusion.UNCALCULABLE, reason);
    }

    // ---------------------------------------------------------------- basis resolution (cached)

    private CellBasis resolveCellBasis(ProductListing cell) {
        try {
            return new CellBasis(priceCalculator.resolveCellBasis(cell), null);
        } catch (RuntimeException e) {
            log.debug("Repricing: cell {} has no pricing basis ({})", cell.getId(), e.getMessage());
            return new CellBasis(null, e.getMessage());
        }
    }

    private OptionBasis resolveOptionBasis(PriceCalculator.CellPricingBasis cellBasis, ProductListing cell,
                                           MasterProductOption masterOption) {
        try {
            return new OptionBasis(priceCalculator.resolveBasis(cellBasis, cell, masterOption), null);
        } catch (RuntimeException e) {
            log.debug("Repricing: cell {} has no delivery/box basis ({})", cell.getId(), e.getMessage());
            return new OptionBasis(null, e.getMessage());
        }
    }

    /** ⚠️ id 만 읽는다 — LAZY 프록시라 여기서 초기화하면 옵션마다 쿼리가 하나씩 더 나간다. */
    private static Long masterOptionId(ProductListingOption option) {
        MasterProductOption masterOption = option.getMasterProductOption();
        return masterOption == null ? null : masterOption.getId();
    }

    private static String groupKey(ProductListing cell) {
        return cell.getSeller().getId() + "|" + cell.getPlatform().name();
    }

    /** 해석 결과 또는 그 실패 사유. 실패도 캐시한다 — 같은 셀에서 같은 예외를 옵션마다 다시 던지지 않는다. */
    private record CellBasis(PriceCalculator.CellPricingBasis basis, String error) {
    }

    private record OptionBasis(PriceCalculator.PricingBasis basis, String error) {
    }

    /** 판매자 × 채널 집계기. {@code scope} 와 무관하게 <b>대상 전부</b>를 센다(D18). */
    private static final class Accumulator {
        private final Long sellerId;
        private final String sellerName;
        private final String platform;
        private int optionCount;
        private int belowCount;
        private int belowManualCount;
        private int pendingPushCount;
        private BigDecimal targetMarginRate;

        private Accumulator(Long sellerId, String sellerName, String platform) {
            this.sellerId = sellerId;
            this.sellerName = sellerName;
            this.platform = platform;
        }

        private void add(Row row, PriceCalculator.CellPricingBasis basis) {
            optionCount++;
            if (row.below()) {
                if (row.excluded() == Exclusion.MANUAL) {
                    belowManualCount++;
                } else {
                    belowCount++;
                }
            }
            if (row.pendingPush()) {
                pendingPushCount++;
            }
            if (targetMarginRate == null && basis != null) {
                targetMarginRate = basis.targetMarginRate();
            }
        }

        private Group toGroup() {
            return new Group(sellerId, sellerName, platform, optionCount, belowCount, belowManualCount,
                    pendingPushCount, targetMarginRate);
        }
    }
}
