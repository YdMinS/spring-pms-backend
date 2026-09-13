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
     * @param belowCount       대응 필요 옵션 수. 🔴 2609_43 D1 이후 <b>직접 지정가도 포함</b>한다 — 화면의
     *                         행 수와 이 숫자가 맞아야 한다
     * @param belowManualCount 그중 <b>가격을 사람이 소유한</b>(직접 지정가) 옵션 수. 🔴 2609_43 D1 이후로는
     *                         「실행에서 빠지는 수」가 아니다 — 직접 입력·마켓 반영은 된다. {@code belowCount}
     *                         의 부분집합이라 두 숫자를 더하면 안 된다
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
     * @param excluded     🔴 2609_43 D1 이후 {@code MANUAL} 은 <b>공식 재계산에서만</b> 빠진다는 뜻이다
     *                     (직접 입력·마켓 반영은 된다). {@code UNCALCULABLE} 은 여전히 실행 대상이 아니다.
     *                     null = 제약 없음
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
        /**
         * 사용자가 직접 정한 가격(2609_19). 🔴 2609_43 D1·D2: <b>공식 재계산만</b> 건너뛴다 — 직접 입력과
         * 마켓 반영은 이 행에서도 실행된다. 화면은 「가격을 사람이 소유한다」는 배지로 쓴다.
         */
        MANUAL,
        /** 수수료·배송·박스·마진 중 하나가 없거나 판정 가격이 0 이하라 마진을 낼 수 없다. */
        UNCALCULABLE
    }
}
