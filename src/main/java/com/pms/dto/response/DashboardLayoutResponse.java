package com.pms.dto.response;

import com.pms.domain.DashboardCard;

import java.util.List;

/**
 * The dashboard of the logged-in member (FEATURE_2610_29 D6 · D9 · D19).
 *
 * @param admin true = the member is ADMIN — the screens show the admin cards then (D9)
 * @param saved false = the member has not saved yet; {@code cards} is empty and the screen draws the default cards
 *              (D19)
 * @param cards the chosen cards in stored order
 */
public record DashboardLayoutResponse(boolean admin, boolean saved, List<DashboardCard> cards) {
}
