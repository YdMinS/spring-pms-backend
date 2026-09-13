package com.pms.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * 마진 경보 조회 결과(FEATURE_2609_39 / PLAN D3·D15·D23·D25). <b>아무것도 저장하지 않는다</b> — 지금 마켓에
 * 걸린 가격을 기준으로 실마진을 계산해 보여줄 뿐이고, 재계산·전송은 사람이 따로 실행한다(D1·D7).
 *
 * <p>{@code groups} 는 <b>대상 전부</b>를 센다(scope 와 무관). {@code rows} 만 scope 로 걸러진다 — 화면이
 * "대상 340건 중 대응 필요 12건"을 말할 수 있어야 하기 때문이다(D18).</p>
 */
public record RepricingCandidatesResponse(List<Group> groups, List<Row> rows) {

    /**
     * 판매자 × 채널 집계. 실행 단위가 이 축이다(D11).
     *
     * @param optionCount      판정 대상 옵션 수(제외 규칙을 통과해 행으로 내려간 것)
     * @param belowCount       대응 필요 + <b>실행 가능</b>(AUTO 가격) 옵션 수
     * @param belowManualCount 대응 필요지만 직접 지정가라 실행에서 빠지는 옵션 수(D23 — 편입 셀이 여기 쌓인다)
     * @param pendingPushCount 아직 안 밀린 옵션 수. 🔴 {@code marketPrice} 가 null 인 행은 「알 수 없음」이라
     *                         세지 않는다(D15)
     * @param targetMarginRate 이 판매자×채널의 목표 마진율. 행마다 같은 값이라 그룹에서 한 번만 내려준다
     */
    public record Group(Long sellerId, String sellerName, String platform, int optionCount,
                        int belowCount, int belowManualCount, int pendingPushCount,
                        BigDecimal targetMarginRate) {
    }

    /**
     * 옵션 1건.
     *
     * @param judgedPrice  마진을 판정한 가격 = {@code marketPrice ?? sellingPrice}(D15)
     * @param newPrice     지금 공식으로 다시 계산한 판매가. 계산 불가면 null
     * @param pendingPush  {@code marketPrice != null && marketPrice != sellingPrice}. 🔴 null 은 false(D15)
     * @param below        대응 필요 여부. 직접 지정가·실행 불가 행도 똑같이 판정한다(D23)
     * @param excluded     실행에서 빠지는 사유. null = 실행 가능
     * @param sellerId     행이 속한 그룹을 화면이 다시 계산하지 않도록 함께 내려준다
     */
    public record Row(Long listingId, String listingName, Long optionId, String optionName,
                      Long sellerId, String platform,
                      BigDecimal judgedPrice, BigDecimal costSum, BigDecimal delivery, BigDecimal box,
                      BigDecimal feeAmount, BigDecimal marginAmount, BigDecimal marginRate,
                      BigDecimal newPrice, BigDecimal marketPrice, BigDecimal sellingPrice,
                      boolean pendingPush, boolean below, Exclusion excluded, String excludedReason) {
    }

    /** 실행(재계산·마켓 반영)에서 빠지는 사유. 값은 서버가 정하고 화면은 라벨만 붙인다. */
    public enum Exclusion {
        /** 사용자가 직접 정한 가격(2609_19). 경보는 하되 실행하지 않는다(D23). */
        MANUAL,
        /** 수수료·배송·박스·마진 중 하나가 없거나 판정 가격이 0 이하라 마진을 낼 수 없다. */
        UNCALCULABLE
    }
}
