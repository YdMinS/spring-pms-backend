package com.pms.service.packing;

import com.pms.dto.response.PackingSavingsBoxRow;
import com.pms.dto.response.PackingSavingsOptionRow;
import com.pms.dto.response.PackingSavingsSummary;

import java.time.LocalDate;
import java.util.List;

/**
 * 포장 절약 집계 (FEATURE_2609_41 / PLAN 2609_41 S1 ~ S16).
 *
 * <p>2609_40 이 박스를 완료할 때마다 남긴 네 값({@code expected_box_cost}·{@code actual_box_cost}
 * ·{@code expected_delivery_cost}·{@code box_package_id})에서 <b>상자 절약</b>과 <b>택배비 절약</b>을
 * 계산해 전체·옵션별·상자별로 접어 내려준다.
 *
 * <p>🔴 <b>계산 규칙의 소유자는 이 서비스 하나</b>다. 절약 컬럼을 새로 저장하지 않고(S11) 조회할 때 계산하며,
 * 손익 계산식({@code SalesStatsServiceImpl})은 <b>한 줄도 건드리지 않는다</b>(S6) — 절약은 별도 카드로 보여주고
 * "이만큼은 추정 순이익에 더해진다"고 설명한다.
 *
 * <p>🔴 <b>기준일은 포장 완료일({@code packed_at})</b>이다(S7). 매출 화면의 기준일(주문일)과 다르다 —
 * 화면이 이 차이를 한 줄로 밝혀야 한다. 기간 상한은 매출 쿼리와 같게 배타(다음 날 00:00)다.
 *
 * <p>🔴 축은 <b>판매자·기간·옵션·상자</b>뿐이다(S13). 포장은 창고 행위라 채널과 무관하므로 채널 축을 넣지 않는다.
 *
 * <p>세 메서드는 같은 계산을 공유한다 — 합계와 옵션별·상자별이 서로 어긋날 자리를 만들지 않는다.
 */
public interface PackingSavingsService {

    /** 전체 합계 + 재활용·합포장·제외 건수. 기간 기본값은 매출 화면과 같다({@code to}=오늘, {@code from}=이번 달 1일). */
    PackingSavingsSummary summary(LocalDate from, LocalDate to, Long sellerId);

    /**
     * 옵션별 — 🔴 <b>행의 키는 마스터 옵션</b>이다(S16). 배분은 채널 옵션 단위로 하고 응답을 만들 때 합친다.
     * 합계는 {@link #summary}와 정확히 일치한다(반올림 잔돈 포함, S5).
     */
    List<PackingSavingsOptionRow> byOption(LocalDate from, LocalDate to, Long sellerId);

    /** 상자별 — "재활용 상자를 더 모을 가치가 있나"에 답하는 숫자(S10). */
    List<PackingSavingsBoxRow> byBox(LocalDate from, LocalDate to, Long sellerId);
}
