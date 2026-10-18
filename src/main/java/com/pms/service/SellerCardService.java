package com.pms.service;

import com.pms.domain.SellerCard;
import com.pms.dto.request.SellerCardCreateRequest;
import com.pms.dto.request.SellerCardUpdateRequest;
import com.pms.dto.response.SellerCardView;

import java.util.List;

/**
 * Seller cards — the payer seller of a receipt is told by its card (FEATURE_2610_18 D29 · D52 · D91 · D92).
 *
 * <p>🔴 The full-number check lives only in {@link SellerCardServiceImpl} (create and update share it).</p>
 *
 * @see com.pms.controller.SellerCardController
 */
public interface SellerCardService {

    /** Visible cards of every seller, seller id then card id. */
    List<SellerCardView> getVisible();

    /** Register a card. 13 or more digits in a row (hyphens and spaces removed) → 400 「가려진 번호만 넣으세요」. */
    SellerCardView create(SellerCardCreateRequest request);

    /** Edit alias and masked number (same check as create). Hidden or missing card → 404. */
    SellerCardView update(Long cardId, SellerCardUpdateRequest request);

    /** [삭제] = hide (D91). Hidden or missing card → 404. */
    void hide(Long cardId);

    /** A visible card, for the receipt check-in (D87). Hidden or missing card → 404. */
    SellerCard requireVisible(Long cardId);
}
