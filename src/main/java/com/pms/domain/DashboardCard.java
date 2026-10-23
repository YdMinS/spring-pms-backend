package com.pms.domain;

/**
 * One chosen dashboard card (FEATURE_2610_29 D6 · D11) — JSON value object stored in {@code member_dashboard.cards}
 * through {@link com.pms.domain.converter.DashboardCardListConverter}.
 *
 * <p>The card and its period are shared by web and mobile; the place and size are stored per platform (D11). A
 * platform's fields are null until that platform saves the card — the screen then puts it at the bottom with the
 * card's first size (D11 · D15).</p>
 *
 * @param cardType    the card (D8)
 * @param period      the chosen period, null = the card's default period or no period (D22 · D23)
 * @param webX        web column, 0..7
 * @param webY        web row, 0..
 * @param webW        web width in cells
 * @param webH        web height in cells
 * @param mobileOrder mobile position in the card order, 0..
 * @param mobileW     mobile width in cells
 */
public record DashboardCard(
        DashboardCardType cardType,
        DashboardPeriod period,
        Integer webX,
        Integer webY,
        Integer webW,
        Integer webH,
        Integer mobileOrder,
        Integer mobileW) {
}
