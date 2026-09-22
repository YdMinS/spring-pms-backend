package com.pms.service.listing;

import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.repository.MasterProductOptionItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 셀 옵션의 구성품을 마스터에서 얻는 <b>단 하나의 경로</b> (FEATURE_2609_71 / 01).
 *
 * <pre>
 * ProductListingOption (셀 옵션)
 *   → ProductListingOption.masterProductOption   (null = 채널 전용)
 *       → MasterProductOptionItem[]              (물품 × 수량)
 * </pre>
 *
 * <p>🔴 <b>정본은 마스터 BOM 이다.</b> 셀 BOM({@code product_listing_product})은 마스터 이전에 기획된
 * 사본이고, 실측(2026-09-22 · 마스터 400 · 셀 200)에서 셀 전용 라인 0 · 채널 전용 옵션 0 으로 확인됐다.
 * 재고·원가를 다루는 {@link com.pms.service.stock.OrderLineExpander} 는 이미 마스터만 본다 — 이 컴포넌트는
 * 나머지 읽기(구매목록·가격·상품명·상세·썸네일·응답)를 같은 정본으로 모은다.
 *
 * <p>⚠️ 채널 전용 옵션({@code masterProductOption == null})은 구성품을 알 수 없다. 빈 목록이 아니라
 * {@link Bom#UNMAPPED} 로 돌려준다 — 조용히 0 으로 처리하면 원가·발주량이 소리 없이 틀린다. 마스터
 * 하드 삭제(2609_72)가 들어오면 이 상태가 실사용 경로가 된다.
 *
 * <p>🔴 호출 규칙: 여러 옵션을 다루면 {@link #forOptions(Collection)} 를 쓴다. 반복문 안에서
 * {@link #forOption(ProductListingOption)} 을 부르면 옵션마다 쿼리가 나간다(N+1).
 */
@Component
@RequiredArgsConstructor
public class CellBomResolver {

    private final MasterProductOptionItemRepository masterProductOptionItemRepository;

    /**
     * 구성품 1줄.
     *
     * <p>⚠️ {@link Product} 엔티티를 그대로 싣는다 — 읽는 쪽이 이름만 쓰지 않는다. 마진 계산은
     * {@code price}, 상품명 생성은 {@code brand}, 썸네일은 {@code imageUrl} 을 읽는다.
     * {@code findWithProductByOptionIdIn} 이 join fetch 로 이미 초기화해 둔 인스턴스라 추가 쿼리는 없다.
     *
     * @param id      출처가 된 {@link MasterProductOptionItem} 의 id
     * @param product 구성 물품 (join fetch 로 초기화됨)
     */
    public record Line(Long id, Product product, int quantity) {

        public Long productId() {
            return product == null ? null : product.getId();
        }

        public String productName() {
            return product == null ? null : product.getProductName();
        }
    }

    /**
     * 옵션 1개의 구성품.
     *
     * @param lines    구성품 (물품 id 오름차순 — 생성물이 실행마다 달라지지 않게)
     * @param unmapped 마스터 옵션에 연결되지 않아 <b>구성품을 알 수 없음</b>. 「구성품이 0개」와 다르다.
     */
    public record Bom(List<Line> lines, boolean unmapped) {

        /**
         * 구성품을 알 수 없는 옵션. ⚠️ 정적 팩터리 이름을 {@code unmapped()} 로 둘 수 없다 —
         * record 의 접근자와 이름이 겹친다. 불변이라 상수 하나를 공유해도 안전하다.
         */
        public static final Bom UNMAPPED = new Bom(List.of(), true);

        public static Bom of(List<Line> lines) {
            return new Bom(lines, false);
        }

        /** 구성품을 하나도 못 얻었다 (미매핑이거나 마스터 옵션이 비었거나). */
        public boolean isEmpty() {
            return lines.isEmpty();
        }

        /** 첫 구성품 또는 null — 썸네일·상세의 「대표 물품」 용도. */
        public Line first() {
            return lines.isEmpty() ? null : lines.get(0);
        }
    }

    /** 옵션 하나. 채널 전용이면 {@link Bom#UNMAPPED}. */
    public Bom forOption(ProductListingOption option) {
        if (option == null || option.isChannelOnly()) {
            return Bom.UNMAPPED;
        }
        Long masterOptionId = option.getMasterProductOption().getId();
        return Bom.of(toLines(masterProductOptionItemRepository.findWithProductByOptionIdIn(
                List.of(masterOptionId))));
    }

    /**
     * 여러 옵션을 한 번에 — 마스터 옵션이 몇 개든 <b>쿼리 1회</b>.
     *
     * @return optionId → Bom. 입력에 있던 모든 옵션에 대해 항목이 있다(채널 전용은 {@code unmapped}).
     */
    public Map<Long, Bom> forOptions(Collection<ProductListingOption> options) {
        Map<Long, Bom> result = new LinkedHashMap<>();
        if (options == null || options.isEmpty()) {
            return result;
        }

        // ⚠️ masterProductOption 은 LAZY 프록시라도 id 만 읽으면 초기화되지 않는다 — 여기서 이름을 읽지 말 것.
        List<Long> masterOptionIds = options.stream()
                .filter(Objects::nonNull)
                .map(ProductListingOption::getMasterProductOption)
                .filter(Objects::nonNull)
                .map(MasterProductOption::getId)
                .distinct()
                .toList();
        Map<Long, List<MasterProductOptionItem>> itemsByMasterOption = masterOptionIds.isEmpty()
                ? Map.of()
                : masterProductOptionItemRepository.findWithProductByOptionIdIn(masterOptionIds).stream()
                        .collect(Collectors.groupingBy(item -> item.getOption().getId()));

        for (ProductListingOption option : options) {
            if (option == null) {
                continue;
            }
            if (option.isChannelOnly()) {
                result.put(option.getId(), Bom.UNMAPPED);
                continue;
            }
            result.put(option.getId(), Bom.of(toLines(itemsByMasterOption
                    .getOrDefault(option.getMasterProductOption().getId(), List.of()))));
        }
        return result;
    }

    /** 물품 id 오름차순 — 상품명·상세·썸네일이 실행마다 다른 순서를 보지 않게 한다(기존 생성기들과 같은 규칙). */
    private List<Line> toLines(List<MasterProductOptionItem> items) {
        return items.stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(item -> new Line(item.getId(), item.getProduct(), item.getQuantity()))
                .toList();
    }
}
