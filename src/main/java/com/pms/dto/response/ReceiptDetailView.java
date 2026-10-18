package com.pms.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * One receipt with its photo and every line (FEATURE_2610_18 D93 · D94). {@code photoUrl} = the stored value (S3
 * public URL on dev/prod, disk path on local) — the web runs it through {@code resolveThumbUrl}.
 *
 * <p>{@code payerSellerId} · {@code cardId} · {@code cardMaskedNumber} start the header edit (D100); a hidden card
 * keeps its alias and number here (D91 · D100 ⑥). {@code cardId} null = the card was unknown.</p>
 */
public record ReceiptDetailView(
        Long id,
        LocalDate purchasedOn,
        Long payerSellerId,
        String payerSellerName,
        Long cardId,
        String cardAlias,
        String cardMaskedNumber,
        String photoUrl,
        List<ReceiptLineView> lines
) {}
