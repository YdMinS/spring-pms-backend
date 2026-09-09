package com.pms.dto.response;

import java.util.List;

/**
 * 원가 파급 dry-run (FEATURE_2609_28 / PLAN D4 ②).
 *
 * <p>매입으로 {@code Product.price} 가 움직인 물품 → 그 물품을 쓰는 마스터 → 연결된 셀. <b>아무것도 저장하지
 * 않는다</b> — 이 숫자가 사람이 멈출 수 있는 유일한 지점이다("12개 마스터 / 340개 셀이 바뀝니다").
 *
 * <p>⚠️ {@code masters} 는 <b>실제로 바뀔 셀이 하나라도 있는</b> 마스터만 담는다. 전부 제외된 마스터는
 * 목록에 없고 그 셀들만 {@code skipped} 에 남는다 — 확정(apply)에 넣어봐야 아무 일도 안 일어난다.
 */
public record PropagationPreview(
        List<AffectedMaster> masters,
        int totalMasters,
        int totalCells,
        List<SkippedCell> skipped) {

    /** 파급될 마스터 1건. {@code optionCount} = 판매가가 다시 계산될 셀 옵션 수(MANUAL 제외). */
    public record AffectedMaster(Long masterId, String masterName, int cellCount, int optionCount) {
    }

    /**
     * 제외된 셀 1건과 그 사유. 사유를 함께 주지 않으면 "왜 내 셀이 안 바뀌었나"를 화면이 설명할 수 없다.
     */
    public record SkippedCell(Long cellId, Long masterId, String cellName, SkipReason reason) {
    }

    /** 제외 사유. 값은 서버가 정하고 화면은 라벨만 붙인다. */
    public enum SkipReason {
        /** 아직 마켓에 없는 셀 — 판매가를 다시 계산할 이유가 없다. */
        DRAFT,
        /** 마켓에 올라간 적이 없는 셀(미승인). 로컬만 갱신하고 push 하지 않는 기존 규칙. */
        NOT_APPROVED,
        /** 전 옵션이 수동 지정가(2609_19) — 사용자가 직접 정한 가격은 건드리지 않는다. */
        MANUAL,
        /**
         * 자동생성 자산이 없는 셀. {@code MasterPropagationService.propagate} 가 실제로 건너뛰는 조건이라
         * 여기서도 세지 않는다 — 세면 미리보기 숫자가 실행 결과보다 커진다.
         */
        NO_ASSETS
    }
}
