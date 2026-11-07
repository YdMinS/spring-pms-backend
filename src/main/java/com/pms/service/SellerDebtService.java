package com.pms.service;

import com.pms.dto.request.DebtUnitPriceRequest;
import com.pms.dto.request.SettlementCreateRequest;
import com.pms.dto.response.SellerDebtEntryView;
import com.pms.dto.response.SellerDebtPairView;

import java.util.List;

/**
 * 「정산관리 > 사업자 간 채무」 (FEATURE_2610_19 D36 · D51 · D90 · D137 · D141 ~ D146).
 *
 * <p>The debts themselves are written by {@code LotLedgerService} only. This service reads them, records cash
 * settlements and fills or fixes the unit price behind a 「미기입」 debt. It never looks at the stock link setting
 * (D148 ④).
 */
public interface SellerDebtService {

    /** Seller pairs with a balance or a 「미기입」 debt, largest balance first. */
    List<SellerDebtPairView> pairs();

    /** Every debt and settlement between two sellers, newest day first. */
    List<SellerDebtEntryView> entries(Long sellerAId, Long sellerBId);

    /** Records a cash settlement. Same seller twice → 400 · unknown seller → 404. */
    void settle(SettlementCreateRequest request);

    /** Cancels a settlement (D90) — kept, left out of the balance. Missing → 404 · already cancelled → 400. */
    void cancelSettlement(Long settlementId);

    /**
     * [단가 입력] / [단가 수정] (D141 · D143): writes the unit price into the lot behind the debt, sets the registered
     * price when D144 / D145 / D146 allow it, re-splits and rewrites the product's debts. A lot whose price was not
     * typed here, is not empty and was not filled from the registered price (FEATURE_2611_17 D22) → 400
     * 「이 단가는 여기서 고칠 수 없습니다.」 (D143 ②). Typing a price filled from the registered price drops its
     * 「미기입」 mark.
     */
    void fillUnitPrice(Long debtId, DebtUnitPriceRequest request);
}
