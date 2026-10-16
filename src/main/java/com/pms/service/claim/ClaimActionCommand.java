package com.pms.service.claim;

import com.pms.domain.ClaimAction;

/**
 * The execute request handed to an adapter (FEATURE_2609_21 / PLAN D14).
 *
 * <p>Why not pass the request DTO = the adapter must not know web-layer types.
 *
 * @param deliveryCompanyCode carrier code — a value that already passed the {@code CarrierCodeService} whitelist
 * @param regNumber           optional field of the pickup invoice (registered-mail number)
 * @param rejectCode          reject / hold reason code (used by 05 and the 11st reject / hold actions)
 * @param reasonText          reason text of the 11st reject / hold actions (FEATURE_2610_17 / D34 · D39 ①)
 */
public record ClaimActionCommand(ClaimAction action,
                                 String deliveryCompanyCode,
                                 String invoiceNumber,
                                 String regNumber,
                                 String rejectCode,
                                 String reasonText) {
}
