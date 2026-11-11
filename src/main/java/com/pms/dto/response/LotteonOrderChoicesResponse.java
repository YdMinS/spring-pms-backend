package com.pms.dto.response;

import com.pms.service.claim.ActionChoice;

import java.util.List;

/**
 * LotteON code lists of the order-detail actions (FEATURE_2611_23 / D58) — labels are LotteON's own names; the
 * screens draw these lists and keep none of their own.
 *
 * @param cancelReasons            [주문 취소] [225] reasons (D25)
 * @param cancelRejectReasons      [취소 거부] [59] reasons (D24)
 * @param appointmentReasons       [발송 약정일 안내] [138] reasons (D26)
 * @param appointmentDetailReasons details of the making delay {@code PD_DL} (D26)
 * @param holdReasons              return / exchange hold [141] reasons (D28 ③)
 */
public record LotteonOrderChoicesResponse(List<ActionChoice> cancelReasons, List<ActionChoice> cancelRejectReasons,
                                          List<ActionChoice> appointmentReasons,
                                          List<ActionChoice> appointmentDetailReasons,
                                          List<ActionChoice> holdReasons) {
}
