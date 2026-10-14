package com.pms.service.elevenst;

import com.pms.domain.Platform;
import com.pms.domain.ProductListing;

import java.math.BigDecimal;

/**
 * 11st value rules applied on OUR side (FEATURE_2610_13 / D66 · D71 · D82 · D103).
 *
 * <ul>
 *   <li>Option name: at most {@link #MAX_NAME_BYTES} EUC-KR bytes (Korean 25 / Latin 50 characters) and none of
 *       {@link #FORBIDDEN}. A name COPIED from the master (channel creation, master rename, [옵션명 일괄 적용],
 *       [마스터 옵션명 반영], back-to-auto) and the option group title go through {@link #sanitizeName} (D66);
 *       a name TYPED by a person on an 11st listing is refused with {@link #INVALID_NAME_MESSAGE} (D71).</li>
 *   <li>Selling price typed by a person on an 11st listing: 10-won unit, else {@link #PRICE_UNIT_MESSAGE} (D71).
 *       Calculated prices are already 10-won units (PriceCalculator).</li>
 * </ul>
 * Only 11st listings are touched ({@link #nameForCell}); every other platform keeps the master name as is.
 */
public final class ElevenstListingRules {

    public static final String FORBIDDEN = "&;'\"%<>#†";
    public static final int MAX_NAME_BYTES = 50;
    public static final String INVALID_NAME_MESSAGE =
            "11번가 옵션 이름은 한글 25자(영문 50자) 이하이고 & ; ' \" % < > # † 를 쓸 수 없습니다.";
    public static final String PRICE_UNIT_MESSAGE = "11번가 판매가는 10원 단위로 넣어 주세요.";

    private static final BigDecimal TEN = BigDecimal.TEN;

    private ElevenstListingRules() {
    }

    public static boolean isElevenst(ProductListing cell) {
        return cell != null && cell.getPlatform() == Platform.ELEVENST;
    }

    /** The name this cell's option takes when it copies {@code masterName}. */
    public static String nameForCell(ProductListing cell, String masterName) {
        return isElevenst(cell) ? sanitizeName(masterName) : masterName;
    }

    /** D66: forbidden characters → a space, then cut at {@link #MAX_NAME_BYTES} EUC-KR bytes. */
    public static String sanitizeName(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder cut = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            String ch = FORBIDDEN.indexOf(cp) >= 0 ? " " : new String(Character.toChars(cp));
            int size = ch.getBytes(ElevenstXml.EUC_KR).length;
            if (bytes + size > MAX_NAME_BYTES) {
                break;
            }
            cut.append(ch);
            bytes += size;
            i += Character.charCount(cp);
        }
        return cut.toString();
    }

    /** D71 · D82: within {@link #MAX_NAME_BYTES} EUC-KR bytes and free of {@link #FORBIDDEN}. */
    public static boolean isValidName(String name) {
        if (name == null) {
            return false;
        }
        boolean forbidden = name.codePoints().anyMatch(cp -> FORBIDDEN.indexOf(cp) >= 0);
        return !forbidden && name.getBytes(ElevenstXml.EUC_KR).length <= MAX_NAME_BYTES;
    }

    /** D71: true when {@code price} is a whole multiple of 10 won. */
    public static boolean isTenWonUnit(BigDecimal price) {
        return price != null && price.remainder(TEN).compareTo(BigDecimal.ZERO) == 0;
    }
}
