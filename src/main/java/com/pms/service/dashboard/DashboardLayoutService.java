package com.pms.service.dashboard;

import com.pms.dto.request.DashboardMobileLayoutRequest;
import com.pms.dto.request.DashboardWebLayoutRequest;
import com.pms.dto.response.DashboardLayoutResponse;

/**
 * The dashboard of the logged-in member (FEATURE_2610_29 D6 · D9 · D11 · D19).
 *
 * <p>Web and mobile share the card list and each card's period; the place and size are stored per platform (D11).
 * A save replaces the card list: a stored card missing from the request is removed, a new card gets this platform's
 * place and no place on the other platform. A save by a non-admin keeps the stored admin cards as they are (D9 — not
 * drawn, not dropped).
 *
 * <p>⚠️ No grid rule (columns, sizes) is checked here — the spec lives on the screens (D10 · D18).
 */
public interface DashboardLayoutService {

    DashboardLayoutResponse layout(String email, boolean admin);

    DashboardLayoutResponse saveWeb(String email, boolean admin, DashboardWebLayoutRequest request);

    DashboardLayoutResponse saveMobile(String email, boolean admin, DashboardMobileLayoutRequest request);
}
