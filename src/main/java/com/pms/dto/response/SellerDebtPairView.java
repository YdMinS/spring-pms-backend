package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * One seller pair on 「사업자 간 채무」 (FEATURE_2610_19 D51 · D137).
 *
 * <p>sellerA = the smaller seller id. {@code debtorId} = who owes the {@code balance} to the other (null = 0).
 * The balance nets borrowed shares both ways and the settlements that are not cancelled; 「미기입」 shares are left
 * out and counted in {@code unknownCount}. A pair with balance 0 and no 「미기입」 is not listed.
 */
public record SellerDebtPairView(
        Long sellerAId,
        String sellerAName,
        Long sellerBId,
        String sellerBName,
        Long debtorId,
        BigDecimal balance,
        int unknownCount
) {}
