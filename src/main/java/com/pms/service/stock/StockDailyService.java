package com.pms.service.stock;

import com.pms.dto.response.IntakeBarcodeView;
import com.pms.dto.response.StockDailyView;

import java.time.LocalDate;

/**
 * 「그날 입출고 내역」 and the 「입고 작업」 barcode lookup (FEATURE_2610_19 D117 · D119 · D123 · D147).
 *
 * <p>Read only. It never looks at the stock link setting — the menu decides (D148 ④).
 */
public interface StockDailyService {

    /** Every ledger row moved on {@code date} with its purchase-price amount, and the day's undecided products. */
    StockDailyView daily(LocalDate date);

    /** The product that owns {@code barcode} (the packing console's lookup), or not found. */
    IntakeBarcodeView barcode(String barcode);
}
