package com.pms.service.elevenst;

import com.pms.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 11st answered an action call with a non-success {@code result_code} (FEATURE_2610_17). Same 502 and same message
 * as before ({@code result_text (result_code)}); it also carries the two raw values so a caller that reports per
 * group can show them as they came — the order-confirmation {@code FailedBox} (D54).
 */
public class ElevenstRejectedException extends BusinessException {

    private final String resultCode;
    private final String resultText;

    public ElevenstRejectedException(String message, String resultCode, String resultText) {
        super(message, HttpStatus.BAD_GATEWAY);
        this.resultCode = resultCode;
        this.resultText = resultText;
    }

    /** 11st {@code result_code} as given; null when the body had none. */
    public String getResultCode() {
        return resultCode;
    }

    /** 11st {@code result_text} as given; null when the body had none. */
    public String getResultText() {
        return resultText;
    }
}
