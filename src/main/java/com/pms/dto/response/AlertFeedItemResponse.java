package com.pms.dto.response;

import com.pms.domain.AlertType;
import com.pms.domain.ClaimType;

import java.time.LocalDateTime;

/**
 * 처리해야 할 일 1건 (GET /api/alerts, FEATURE_2609_51).
 *
 * <p>🔴 저장된 알림이 아니다 — 결제완료 주문·미완결 클레임·미답변 문의를 조회 시점에 모아 만든
 * <b>파생 값</b>이다(D1). 일이 끝나면 다음 조회부터 사라진다. 확인·읽음 상태는 <b>없다</b>(D2).
 *
 * <p>🔴 구매자 연락처·주소를 담지 않는다(2609_23 D13). 클레임의 {@code requesterName} 도 <b>싣지 않는다</b> —
 * "처리해야 할 일" 목록에 고객 이름이 필요 없다.
 *
 * <p>⚠️ {@code occurredAt} 은 세 소스 모두 마켓이 준 <b>KST 벽시계</b>다(주문일·접수일·문의일).
 * 화면이 서버 낙인 시각용 상대시각 함수를 쓰면 9시간 어긋난다(D8).
 *
 * @param refId           ORDER = {@code orders.id} · CLAIM = {@code order_claim.id} · INQUIRY = {@code customer_inquiry.id}
 * @param externalOrderId ORDER 의 딥링크 키(출고관리 주문번호 검색어, D9). 다른 타입은 참고용
 * @param itemName        ORDER 는 <b>대표 상품 1개</b>(첫 라인)
 * @param itemCount       ORDER 의 상품(라인) 수 — 화면이 {@code 상품 3개} 로 그린다.
 *                        ⚠️ 2026-09-16 이후 <b>메뉴 배지도 주문 단위</b>({@code paidOrders})라 단위 차이는 없다.
 *                        남은 차이는 기간뿐이다 — 메뉴는 상한이 없고 알림은 최근 {@code sync-days} 다
 * @param detail          클레임=사유, 문의=본문 앞부분, 주문=null. 🔴 <b>200자에서 자른다</b>
 * @param occurredAt      ORDER=주문일 · CLAIM=접수일 · INQUIRY=문의일. 정렬 기준
 * @param claimType       CLAIM 일 때만 채운다(RETURN·EXCHANGE) — 웹이 반품/교환 탭을 고르는 데 쓴다(D9)
 */
public record AlertFeedItemResponse(
        AlertType alertType,
        Long refId,
        String externalOrderId,
        String platform,
        String sellerName,
        String itemName,
        Integer itemCount,
        String detail,
        LocalDateTime occurredAt,
        ClaimType claimType) {
}
