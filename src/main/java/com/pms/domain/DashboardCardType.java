package com.pms.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The cards a dashboard can show (FEATURE_2610_29 D8), in the D8 order. {@code adminOnly} = the card reads an
 * endpoint of the ADMIN role and is hidden from other roles (D9). {@code periods} = the periods the card may store (D22 ·
 * D23); an empty set = the card has no period.
 *
 * <p>⚠️ Grid sizes are not here — they live on the screens (D10).</p>
 */
public enum DashboardCardType {
    PAID_ORDERS(false, EnumSet.noneOf(DashboardPeriod.class)),
    NEW_ORDERS(false, EnumSet.noneOf(DashboardPeriod.class)),
    OPEN_CLAIMS(false, EnumSet.noneOf(DashboardPeriod.class)),
    UNANSWERED_INQUIRIES(false, EnumSet.noneOf(DashboardPeriod.class)),
    PERIOD_SALES(true, EnumSet.of(DashboardPeriod.TODAY, DashboardPeriod.DAYS_7, DashboardPeriod.THIS_MONTH)),
    SETTLEMENT_PAYOUTS(true, EnumSet.noneOf(DashboardPeriod.class)),
    PURCHASE_LIST(true, EnumSet.noneOf(DashboardPeriod.class)),
    RESERVED_SHIPMENTS(true, EnumSet.noneOf(DashboardPeriod.class)),
    DAILY_SALES_CHART(true, EnumSet.of(DashboardPeriod.DAYS_7, DashboardPeriod.DAYS_30)),
    CHANNEL_SALES_CHART(true, EnumSet.of(DashboardPeriod.DAYS_7, DashboardPeriod.DAYS_30));

    private final boolean adminOnly;
    private final Set<DashboardPeriod> periods;

    DashboardCardType(boolean adminOnly, Set<DashboardPeriod> periods) {
        this.adminOnly = adminOnly;
        this.periods = periods;
    }

    public boolean isAdminOnly() {
        return adminOnly;
    }

    /** {@code null} is allowed (the screen then uses the card's default period, D22 · D23). */
    public boolean allows(DashboardPeriod period) {
        return period == null || periods.contains(period);
    }
}
