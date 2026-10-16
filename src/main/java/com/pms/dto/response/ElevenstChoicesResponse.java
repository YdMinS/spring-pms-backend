package com.pms.dto.response;

import com.pms.service.claim.ActionChoice;

import java.util.List;

/**
 * Code lists of the 11st order-detail actions (FEATURE_2610_17 / D61 · D62 · D63) — the 11st guide's own names, word
 * for word. The screens show them as given and keep no code→label map.
 *
 * @param cancelReasons      [주문 취소] of an 11st line — sale refusal 1638 (6 reasons, D61)
 * @param faultRejectReasons [책임사유 오류로 거부] 1745 (2 reasons, D62)
 * @param delayReasons       [발송지연 안내] 6728 (5 reasons, D63)
 */
public record ElevenstChoicesResponse(List<ActionChoice> cancelReasons, List<ActionChoice> faultRejectReasons,
                                      List<ActionChoice> delayReasons) {
}
