package com.pms.dto.response;

import java.util.List;

/**
 * 출고 대상 조회 응답 (FEATURE_2609_28 / PLAN D11·D13).
 *
 * <p>{@code unexpanded} 는 비어 있는 것이 정상이고, 값이 있으면 <b>고칠 것이 있다는 신호</b>다.
 */
public record OutboundResponse(List<OutboundOrderView> orders, List<OutboundUnexpandedView> unexpanded) {
}
