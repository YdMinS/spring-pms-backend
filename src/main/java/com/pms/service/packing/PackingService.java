package com.pms.service.packing;

import com.pms.dto.request.BoxCandidateRequest;
import com.pms.dto.request.ParcelCompleteRequest;
import com.pms.dto.response.BarcodeLookupResponse;
import com.pms.dto.response.PackingScanResponse;
import com.pms.dto.response.ParcelCloseResponse;
import com.pms.dto.response.ParcelCompleteResponse;
import com.pms.dto.response.PendingParcelView;

import java.util.List;

/**
 * 포장 콘솔 — 송장 스캔부터 박스 단위 출고까지 (FEATURE_2609_40 / PLAN D9 ~ D19 · D27 ~ D32).
 *
 * <p><b>이 서비스가 소유하는 것</b>: 박스(=송장 1장)의 수명. {@code PENDING} 으로 태어난 박스를
 * {@link #complete}(포장했다) 또는 {@link #unused}(안 썼다) 로 닫는다.
 *
 * <p>🔴 <b>소유하지 않는 것 3가지</b> — 전부 기존 주인에게 위임한다. 여기에 두 번째 구현을 만들면
 * 화면마다 다른 숫자를 말하기 시작한다:
 * <pre>
 *   잔량      → StockOutService.remaining   (필요 − STOCK_OUT 합계, D28)
 *   출고 기록 → StockOutService.confirm     (잔량 초과·전개 실패 차단 + 원가 스냅샷까지, D29)
 *   상자 추천 → BoxRecipeService.candidates (기억표, D22 ~ D25)
 * </pre>
 *
 * <p>🔴 <b>주문 상태로 거르지 않는다</b>(D27). 포장 시점의 주문은 이미 배송지시({@code SHIPPED})이므로
 * 출고 화면의 상태 필터를 가져오면 스캔한 송장이 전부 빈 목록이 된다. 빠지는 것은 전량 취소 라인뿐이다.
 *
 * <p>🔴 <b>작업 대상은 상태가 아니라 잔량으로 가른다</b>(D31): {@code PENDING} 이면서 잔량 &gt; 0.
 * 동기화 백필이 과거 배송 묶음에도 {@code PENDING} 박스를 만들기 때문이다.
 *
 * <p>사용 예 (Controller):
 * <pre>
 *   PackingScanResponse scanned = packingService.scan("123456789012");
 *   packingService.complete(scanned.parcel().id(), request);
 * </pre>
 *
 * @see com.pms.controller.PackingController
 */
public interface PackingService {

    /**
     * 송장번호 → 박스 + 담을 것 + 상자 후보.
     *
     * <p>못 찾으면 404. 이미 닫힌 박스({@code PACKED}/{@code UNUSED})는 <b>예외가 아니라</b> 그 상태를
     * 달고 200 으로 돌아온다 — 화면이 "이미 출고됨" / "사용하지 않은 박스"를 띄운다.
     */
    PackingScanResponse scan(String invoiceNumber);

    /**
     * 작업 대상 박스 목록 (D16).
     *
     * @param sellerId null = 전 판매자
     */
    List<PendingParcelView> pending(Long sellerId);

    /**
     * 못 맞춘 바코드를 물어본다 (D11 의 오류 경로).
     *
     * @param parcelId 🔴 필수 — 없으면 「이 박스에 담을 수 있는가」를 판정할 기준이 없다(400)
     */
    BarcodeLookupResponse barcode(String value, Long parcelId);

    /** 담은 조합 → 상자 후보. {@link BoxRecipeService#candidates} 에 그대로 위임한다(D23). */
    List<BoxCandidate> boxCandidates(BoxCandidateRequest request);

    /**
     * [이 박스 완료] — 박스 내용 저장 + 출고 기록 + 절약 기록 + 상자 기억 (D14 · D18 · D19 · D24).
     *
     * <p>🔴 <b>멱등</b>하다(D15): 이미 {@code PACKED} 면 아무것도 만들지 않고 현재 상태를 돌려준다.
     * <p>🔴 검증은 전부 저장 <b>전에</b> 끝난다 — 하나라도 걸리면 아무것도 저장되지 않는다.
     */
    ParcelCompleteResponse complete(Long parcelId, ParcelCompleteRequest request);

    /**
     * 「사용하지 않은 박스」로 닫는다 (D32). 출고·기억·절약을 남기지 않는다.
     *
     * <p>이미 {@code UNUSED} 면 멱등, 이미 {@code PACKED} 면 400 이다.
     */
    ParcelCloseResponse unused(Long parcelId);
}
