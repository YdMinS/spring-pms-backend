package com.pms.service;

/**
 * The single place that knows how an invoice number typed by a human becomes the value we store and send.
 *
 * <p><b>Why</b>: Coupang rejects an invoice number that carries hyphens, but couriers print (and people paste)
 * {@code 2558-2825-3026} or {@code 2558 2825 3026}. Stripping the separators only right before the Coupang call
 * would leave the hyphenated form in our own tables, so a scan or a reconciliation would no longer match what we
 * sent. Every entry point normalizes instead, so <b>what we store and what we send are the same string</b>.
 *
 * <p><b>Callers</b> (every path an invoice number can enter through):
 * <ul>
 *   <li>{@link ShipmentConfirmServiceImpl} — single manual shipment, and the courier result xlsx (bulk)</li>
 *   <li>{@link ShipmentParcelRecorder} — the only writer of {@code shipment_parcel.invoice_number}
 *       (manual · bulk · order-sync backfill all land here)</li>
 *   <li>{@link com.pms.service.packing.PackingServiceImpl#scan(String)} — lookup side; a scanned/typed
 *       hyphenated value must find the stored (normalized) parcel</li>
 *   <li>{@link com.pms.service.claim.ClaimActionServiceImpl} ·
 *       {@link com.pms.service.claim.ClaimCollectInvoiceRecorder} ·
 *       {@link com.pms.service.claim.CoupangClaimActionAdapter} — claim collect invoices</li>
 * </ul>
 *
 * <p><b>Usage</b>:
 * <pre>
 *   String invoice = InvoiceNumbers.normalizeRequired(request.invoiceNumber()); // "2558-2825-3026" → "255828253026"
 *   String scanned = InvoiceNumbers.normalize(rawScan);                         // null → null, "" → ""
 * </pre>
 *
 * <p>⚠️ Only hyphens and whitespace are removed. Anything else is left untouched on purpose — the market decides
 * whether the rest of the string is a valid invoice number, and silently dropping characters here would turn a
 * typo into a wrong-but-accepted invoice number.
 *
 * <p>❌ Do NOT scatter {@code replace("-", "")} across the shipment/claim/packing paths. One rule, one place —
 * otherwise the value we store and the value we send drift apart again.
 */
public final class InvoiceNumbers {

    /** Hyphen-minus and any whitespace — the separators couriers print and people paste. */
    private static final String SEPARATORS = "[\\s\\u00A0-]";

    private InvoiceNumbers() {
    }

    /**
     * Strips hyphens and whitespace. {@code null} stays {@code null} (an absent invoice number is not an empty
     * one), and a value made only of separators becomes {@code ""} — callers that require a value use
     * {@link #normalizeRequired(String)}.
     */
    public static String normalize(String raw) {
        return raw == null ? null : raw.replaceAll(SEPARATORS, "");
    }

    /**
     * Same as {@link #normalize(String)} for a field the user must fill in.
     *
     * @throws IllegalArgumentException when nothing is left after normalizing (→ 400), so we never send an empty
     *                                  invoice number to the market
     */
    public static String normalizeRequired(String raw) {
        String normalized = normalize(raw);
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException("송장번호는 필수입니다");
        }
        return normalized;
    }
}
