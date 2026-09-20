package com.pms.dto.response;

import java.util.List;

/**
 * 바코드 추출 한 번의 결과 (FEATURE_2609_65).
 *
 * @param requested 요청한 물품 수
 * @param extracted 실제로 저장된 수 — 나머지는 원인별로 {@code items} 에 들어 있다
 * @param items     요청 순서대로의 물품별 결과
 */
public record BarcodeExtractionResult(int requested, int extracted, List<BarcodeExtractionItem> items) {
}
