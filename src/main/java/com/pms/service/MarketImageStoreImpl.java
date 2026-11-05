package com.pms.service;

import com.pms.config.ImageStorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link MarketImageStore} implementation — {@link ProductImageLoader} reads, {@link ImageStorageService} stores. */
@Service
@RequiredArgsConstructor
public class MarketImageStoreImpl implements MarketImageStore {

    private static final String STORAGE_CATEGORY = "thumbnails";

    private final ProductImageLoader productImageLoader;
    private final ImageStorageService imageStorageService;
    private final ImageStorageProperties imageStorageProperties;

    @Override
    public String copy(String marketUrl, Long listingId, int index) {
        String target = marketUrl == null ? "" : marketUrl.trim();
        if (target.startsWith("http://")) {
            target = "https://" + target.substring("http://".length());
        }
        // A non-https value would make the loader read our own storage — never accept it.
        if (!target.startsWith("https://")) {
            throw new IllegalArgumentException("마켓 사진 주소가 올바르지 않습니다: " + marketUrl);
        }
        byte[] bytes = productImageLoader.loadUrl(target);
        if (bytes.length > imageStorageProperties.getMaxFileSize()) {
            throw new IllegalArgumentException("마켓 사진 크기가 허용치를 넘습니다: " + marketUrl);
        }
        Format format = detect(bytes, marketUrl);
        return imageStorageService.uploadBytes(bytes, STORAGE_CATEGORY,
                "listing_" + listingId + "_market_" + System.currentTimeMillis() + "_" + index + "." + format.extension(),
                format.contentType());
    }

    /** The format by the first bytes — the market's {@code Content-Type} is not trusted. */
    private static Format detect(byte[] b, String marketUrl) {
        if (b.length >= 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF) {
            return new Format("image/jpeg", "jpg");
        }
        if (b.length >= 4 && b[0] == (byte) 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return new Format("image/png", "png");
        }
        if (b.length >= 4 && b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38) {
            return new Format("image/gif", "gif");
        }
        if (b.length >= 12 && b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
                && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) {
            return new Format("image/webp", "webp");
        }
        throw new IllegalArgumentException("마켓 사진이 이미지 파일이 아닙니다: " + marketUrl);
    }

    private record Format(String contentType, String extension) {
    }
}
