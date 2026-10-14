package com.pms.service.elevenst;

import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** FEATURE_2610_13 / D66 · D71 · D82. */
class ElevenstListingRulesTest {

    @Test
    void sanitize_replacesForbiddenWithSpace_andCutsAtFiftyBytes() {
        assertThat(ElevenstListingRules.sanitizeName("A&B;C'D\"E%F<G>H#I†J")).isEqualTo("A B C D E F G H I J");
        assertThat(ElevenstListingRules.sanitizeName("가".repeat(30))).isEqualTo("가".repeat(25));
        assertThat(ElevenstListingRules.sanitizeName("a".repeat(60))).isEqualTo("a".repeat(50));
    }

    @Test
    void nameForCell_onlyTouchesElevenst() {
        ProductListing elevenst = ProductListing.builder().platform(Platform.ELEVENST).build();
        ProductListing coupang = ProductListing.builder().platform(Platform.COUPANG).build();
        assertThat(ElevenstListingRules.nameForCell(elevenst, "빨강&파랑")).isEqualTo("빨강 파랑");
        assertThat(ElevenstListingRules.nameForCell(coupang, "빨강&파랑")).isEqualTo("빨강&파랑");
    }

    @Test
    void isValidName_and_isTenWonUnit() {
        assertThat(ElevenstListingRules.isValidName("가".repeat(25))).isTrue();
        assertThat(ElevenstListingRules.isValidName("가".repeat(26))).isFalse();
        assertThat(ElevenstListingRules.isValidName("50%")).isFalse();
        assertThat(ElevenstListingRules.isTenWonUnit(new BigDecimal("12340.00"))).isTrue();
        assertThat(ElevenstListingRules.isTenWonUnit(new BigDecimal("12345.00"))).isFalse();
    }
}
