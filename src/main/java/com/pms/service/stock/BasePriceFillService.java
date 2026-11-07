package com.pms.service.stock;

/**
 * Empty unit prices filled from the product's registered price (FEATURE_2611_17 D15 · D22).
 *
 * <p><b>Purpose</b>: the one place that fills a unit price left empty when it was saved, once the product has a
 * registered price (an 「기타」 intake saved without an amount takes the price at save time instead —
 * {@code StockLedgerServiceImpl}, D25). A filled purchase or lot gets the registered price as its unit
 * price, its total follows ({@code PurchaseRecord.withBasePriceFill}), the base price is not moved by it, and the
 * 「미기입」 mark ({@code price_from_base_at}) is set so the daily view still shows that nobody typed the price.
 * <b>File</b>: {@code service/stock/BasePriceFillService.java}
 *
 * <p><b>Required rule</b>: a path that moves an existing product's {@code Product.price} from empty to a value (0
 * included) calls {@link #fillEmptyPrices} after the product is saved, in the same transaction — today these five
 * (a new product has no intake yet, so product creation does not call it):
 * <ol>
 *   <li>{@code CostPropagationServiceImpl.updateBasePrice} — a purchase moved the registered price</li>
 *   <li>{@code ProductServiceImpl.updateProduct} — 「상품 수정」</li>
 *   <li>{@code SellerDebtServiceImpl.fillUnitPrice} — a lot price typed on the debt screen filled it</li>
 *   <li>{@code ProductMergeService.merge} — the merged product took a price</li>
 *   <li>{@code PurchaseReceiptServiceImpl.editDailyRow} — an opening-stock price typed in the daily row popup (D17)</li>
 * </ol>
 * {@link #fillAllEmptyPrices} is the deploy-time fill of the rows that are empty today
 * ({@code UnitPriceFromBaseBackfillRunner}, once per tenant at start-up — idempotent, a filled row is never empty
 * again).
 *
 * <p><b>Usage</b>:
 * <pre>{@code
 * // the registered price was empty and is not any more
 * if (oldPrice == null && saved.getPrice() != null) {
 *     basePriceFillService.fillEmptyPrices(saved.getId());
 * }
 * }</pre>
 *
 * <p>⚠️ The debts of the product are rewritten ({@code LotLedgerService.rebuildDebts}); the cost of orders that already
 * went out is never recalculated (FEATURE_2611_16 D5).
 * <p>❌ A minus purchase row is never filled (FEATURE_2610_19 D135 ③), and nothing here moves {@code Product.price}.
 */
public interface BasePriceFillService {

    /**
     * The product has a registered price: fill its purchases (quantity &gt; 0) and lots without a unit price, mark them
     * 「미기입」, re-split the undecided lots of the filled shares and rewrite the product's debts. No registered price
     * or nothing empty → nothing changes.
     *
     * @return how many purchases and lots were filled
     */
    int fillEmptyPrices(Long productId);

    /** {@link #fillEmptyPrices} for every product of the current tenant with a registered price and an empty row. */
    int fillAllEmptyPrices();
}
