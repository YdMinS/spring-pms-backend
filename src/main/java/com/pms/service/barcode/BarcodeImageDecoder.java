package com.pms.service.barcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a 1D barcode out of a product photo with ZXing (FEATURE_2609_65).
 *
 * <p><b>Why a decoder and not an LLM.</b> EAN/UPC carry a check digit that the reader itself verifies —
 * when the bars are misread the reader returns <i>nothing</i>. A VLM instead invents a plausible
 * 13-digit number, and a single wrong digit is a <b>different product</b>: picking would then grab the
 * wrong item with no way to notice. This class makes <b>zero external/LLM calls</b> (PLAN §2).</p>
 *
 * <p><b>Candidate formats (4)</b>: {@code EAN_13} · {@code EAN_8} · {@code UPC_A} · {@code CODE_128}.
 * Everything else is excluded on purpose (PLAN/D2) — a wider candidate set buys misreads, and a misread
 * barcode is the worst outcome this feature can produce:</p>
 * <ul>
 *   <li>{@code QR_CODE} / {@code DATA_MATRIX} / {@code AZTEC} / {@code PDF_417} — packaging QR is mostly a
 *       marketing URL; reading it would put a URL into the barcode field.</li>
 *   <li>{@code UPC_E} — ZXing returns the compressed 8-digit form while scanners usually send the expanded
 *       12 digits, so a hit here would guarantee a mismatch with the picking scan (PLAN/D4).</li>
 *   <li>{@code ITF} — no self-checking character; it misreads table borders and background patterns as digits.</li>
 *   <li>{@code CODABAR} / {@code CODE_39} / {@code CODE_93} / {@code RSS_14} / {@code RSS_EXPANDED} — effectively
 *       absent from Korean retail packaging.</li>
 * </ul>
 *
 * <p><b>Attempts</b>: original + one 90° rotation, nothing more (PLAN/D6). ZXing's 1D readers only sweep
 * horizontal lines, so a vertical barcode needs the rotated pass. A 180° pass is pointless — a 1D reader
 * reads a line in both directions. No tiling, upscaling or binarizer tuning: failure is an accepted
 * result here (PLAN §3), and those would turn one product into dozens of decode passes.</p>
 *
 * <p>File: {@code service/barcode/BarcodeImageDecoder.java}.</p>
 */
@Slf4j
@Component
public class BarcodeImageDecoder {

    private static final Map<DecodeHintType, Object> HINTS = Map.of(
            DecodeHintType.TRY_HARDER, Boolean.TRUE,
            DecodeHintType.POSSIBLE_FORMATS, List.of(
                    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                    BarcodeFormat.UPC_A, BarcodeFormat.CODE_128));

    /**
     * Accepted barcode body.
     *
     * <p>🔴 {@code products.barcode_id} is VARCHAR(50), so anything longer cannot be stored at all.
     * 🔴 This is also the safety net against a URL landing in the barcode field ({@code :} and {@code /}
     * do not pass) — QR is already out of the candidate list (PLAN/D2), this blocks it twice.</p>
     */
    private static final Pattern ACCEPTED = Pattern.compile("^[0-9A-Za-z._-]{4,50}$");

    /**
     * Read one barcode from the image.
     *
     * @param imageBytes raw image bytes (as stored — the uploader keeps originals)
     * @return the barcode, or {@link Optional#empty()} when the image holds no readable barcode
     * @throws IllegalArgumentException when the image itself cannot be opened (corrupt / unsupported format)
     */
    public Optional<DecodedBarcode> decode(byte[] imageBytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            throw new IllegalArgumentException("이미지를 열 수 없습니다", e);
        }
        if (image == null) {
            // Unsupported format (webp and friends) lands here too.
            throw new IllegalArgumentException("이미지를 열 수 없습니다");
        }
        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        return read(bitmap).or(() -> read(bitmap.rotateCounterClockwise()));
    }

    private Optional<DecodedBarcode> read(BinaryBitmap bitmap) {
        // ⚠️ A fresh reader per attempt — MultiFormatReader is stateful, a shared field would break
        // under concurrent requests.
        MultiFormatReader reader = new MultiFormatReader();
        Result result;
        try {
            result = reader.decode(bitmap, HINTS);
        } catch (ReaderException e) {
            // NotFoundException (no barcode) — and, through the same parent, a checksum/format failure:
            // 🔴 a failed check digit must come back as "nothing read", never as a guessed value.
            return Optional.empty();
        }
        String text = result.getText();
        if (text == null || !ACCEPTED.matcher(text).matches()) {
            log.debug("barcode decode rejected by body pattern: {}", text);
            return Optional.empty();
        }
        return Optional.of(new DecodedBarcode(text, result.getBarcodeFormat().name()));
    }
}
