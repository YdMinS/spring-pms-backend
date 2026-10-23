package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.DashboardMobileLayoutRequest;
import com.pms.dto.request.DashboardWebLayoutRequest;
import com.pms.dto.response.DashboardLayoutResponse;
import com.pms.service.dashboard.DashboardLayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard cards of the logged-in member (FEATURE_2610_29 D6 · D11).
 *
 * <p>Authority = {@code anyRequest().authenticated()} — USER · GUEST · ADMIN have a dashboard; the service decides
 * which cards a role may show (D9), not the path. No {@code @PreAuthorize}.
 *
 * <p>⚠️ {@code IllegalArgumentException} is mapped to 400 by {@code GlobalExceptionHandler} — do not catch it here.
 */
@RestController
@RequestMapping("/api/dashboard/layout")
@RequiredArgsConstructor
public class DashboardLayoutController {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final DashboardLayoutService dashboardLayoutService;

    @GetMapping
    public ResponseEntity<ResponseDTO<DashboardLayoutResponse>> layout(Authentication authentication) {
        return ResponseEntity.ok(ResponseDTO.success(
                dashboardLayoutService.layout(authentication.getName(), isAdmin(authentication))));
    }

    @PutMapping("/web")
    public ResponseEntity<ResponseDTO<DashboardLayoutResponse>> saveWeb(
            Authentication authentication, @RequestBody DashboardWebLayoutRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                dashboardLayoutService.saveWeb(authentication.getName(), isAdmin(authentication), request)));
    }

    @PutMapping("/mobile")
    public ResponseEntity<ResponseDTO<DashboardLayoutResponse>> saveMobile(
            Authentication authentication, @RequestBody DashboardMobileLayoutRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                dashboardLayoutService.saveMobile(authentication.getName(), isAdmin(authentication), request)));
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ROLE_ADMIN::equals);
    }
}
