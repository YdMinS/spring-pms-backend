package com.pms.dto.request;

import com.pms.domain.DashboardCardType;
import com.pms.domain.DashboardPeriod;

import java.util.List;

/**
 * Save the web dashboard (FEATURE_2610_29 D6 · D11 · D16) — the cards the web screen shows, with their place and
 * size in grid cells. A stored card missing here is removed on both platforms (D11).
 */
public record DashboardWebLayoutRequest(List<Card> cards) {

    /** One card on the web grid: column {@code x} (0..), row {@code y} (0..), width {@code w} and height {@code h}. */
    public record Card(DashboardCardType cardType, DashboardPeriod period, Integer x, Integer y, Integer w, Integer h) {
    }
}
