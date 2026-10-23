package com.pms.dto.request;

import com.pms.domain.DashboardCardType;
import com.pms.domain.DashboardPeriod;

import java.util.List;

/**
 * Save the mobile dashboard (FEATURE_2610_29 D6 · D11 · D16) — the cards the mobile screen shows, with their place in
 * the card order and their width in cells. A stored card missing here is removed on both platforms (D11).
 */
public record DashboardMobileLayoutRequest(List<Card> cards) {

    /** One card on the mobile grid: position {@code order} (0..) and width {@code w}. */
    public record Card(DashboardCardType cardType, DashboardPeriod period, Integer order, Integer w) {
    }
}
