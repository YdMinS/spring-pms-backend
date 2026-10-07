package com.pms.service.barcode;

import com.pms.dto.response.BarcodeScanResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reads a barcode out of an uploaded image and returns only the value (product registration helper).
 *
 * <p>🔴 The image is never stored — no {@code ImageStorageService}, no DB write. Implementation:
 * {@link BarcodeScanServiceImpl}.</p>
 */
public interface BarcodeScanService {

    /**
     * Decode one barcode from the uploaded image.
     *
     * @param file uploaded jpg/png
     * @return the barcode and its format, or both {@code null} when no readable barcode was found
     * @throws com.pms.exception.InvalidImageException when the file is empty or not a jpg/png (400)
     * @throws IllegalArgumentException                when the image cannot be opened (400)
     */
    BarcodeScanResponse scan(MultipartFile file);
}
