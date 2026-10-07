package com.pms.service.barcode;

import com.pms.dto.response.BarcodeScanResponse;
import com.pms.service.ImageValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Stateless barcode scan of an uploaded image.
 *
 * <p>Validation is the shared {@link ImageValidator} (same as every other upload endpoint); decoding is the
 * shared {@link BarcodeImageDecoder} — 🔴 do not add a second decoder or loosen its rules here (4 formats,
 * body pattern, no LLM). No {@code @Transactional}: there is no DB access.</p>
 *
 * <p>File: {@code service/barcode/BarcodeScanServiceImpl.java}.</p>
 */
@Service
@RequiredArgsConstructor
public class BarcodeScanServiceImpl implements BarcodeScanService {

    private final ImageValidator imageValidator;
    private final BarcodeImageDecoder barcodeImageDecoder;

    @Override
    public BarcodeScanResponse scan(MultipartFile file) {
        imageValidator.validate(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded image", e);
        }
        // IllegalArgumentException (image cannot be opened) propagates → 400 via GlobalExceptionHandler.
        return barcodeImageDecoder.decode(bytes)
                .map(decoded -> new BarcodeScanResponse(decoded.text(), decoded.format()))
                .orElseGet(BarcodeScanResponse::notFound);
    }
}
