package com.pms.exception;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Thrown when a product is deleted while masters or channel listing options are still composed of it
 * (FEATURE_2609_69 / A). Deletion is a soft delete, so no FK would ever complain — those rows would just
 * keep pointing at a hidden product and quietly go wrong, which is worse than an error. → 409 Conflict
 * via the generic {@code BusinessException} handler.
 *
 * <p>The blockers are noun phrases produced by {@code ProductUsageService}; this class owns the closing
 * clause so the joined message stays one sentence.</p>
 */
public class ProductInUseException extends BusinessException {

    public ProductInUseException(List<String> blockers) {
        super(String.join(" · ", blockers) + "에 연결되어 있어 삭제할 수 없습니다.", HttpStatus.CONFLICT);
    }
}
