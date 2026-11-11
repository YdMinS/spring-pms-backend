package com.pms.dto.response;

import java.util.List;

/**
 * Result of 「입고 작업」 [입고 저장] (FEATURE_2611_22 D3 ①).
 *
 * @param lotIds the stock rows written, in line order — the 「채울까요?」 window asks for them
 */
public record StockIntakeResult(List<Long> lotIds) {}
