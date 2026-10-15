package com.pms.service;

import com.pms.dto.request.ElevenstNoticesRequest;
import com.pms.dto.request.MarketplaceAccountRequest;
import com.pms.dto.request.OptionCheckSuffixRequest;
import com.pms.dto.response.MarketplaceAccountResponse;

import java.util.List;

/**
 * 외부 플랫폼 계정/자격증명 CRUD.
 *
 * 모든 marketplace_account 접근은 이 Service 를 경유한다 (Controller 가 Repository 직접 호출 금지).
 * secretKey 는 엔티티 컨버터가 암복호화하며, 응답으로는 절대 노출하지 않는다.
 */
public interface MarketplaceAccountService {

    MarketplaceAccountResponse create(MarketplaceAccountRequest req);

    MarketplaceAccountResponse get(Long id);

    List<MarketplaceAccountResponse> list(Long sellerId);

    MarketplaceAccountResponse update(Long id, MarketplaceAccountRequest req);

    /**
     * Replace the channel-level "옵션확인" suffix override (69). Replace semantics: both fields stored as sent,
     * null = inherit (blank suffix normalizes to null). 404 if the account is absent.
     */
    MarketplaceAccountResponse updateRegistrationNameSuffix(Long id, OptionCheckSuffixRequest req);

    /**
     * Replace the 11st channel's notice defaults (FEATURE_2610_16 / D7 · D8 · D16). Blank texts are stored as null;
     * a text over 4000 EUC-KR bytes is a 400. 400 when the account is not 11st, 404 when it is absent.
     */
    void updateElevenstNotices(Long id, ElevenstNoticesRequest req);

    void delete(Long id);
}
