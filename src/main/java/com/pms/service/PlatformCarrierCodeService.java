package com.pms.service;

import com.pms.dto.request.PlatformCarrierCodeRequest;
import com.pms.dto.response.PlatformCarrierCodeResponse;

import java.util.List;

/**
 * 플랫폼별 택배사 코드(platform_carrier_code) 관리 CRUD.
 *
 * ⚠️ 발송처리 조회 전용인 {@link CarrierCodeService}(lookup)와 별개다.
 *    이 서비스는 화면에서 코드를 등록/수정/삭제하는 관리용이며, carrier 하위 중첩 리소스로 다룬다.
 *
 * @see CarrierCodeService (조회 전용 lookup — 혼동 금지)
 */
public interface PlatformCarrierCodeService {

    List<PlatformCarrierCodeResponse> getCodes(Long carrierId);

    PlatformCarrierCodeResponse createCode(Long carrierId, PlatformCarrierCodeRequest req);

    PlatformCarrierCodeResponse updateCode(Long carrierId, Long codeId, PlatformCarrierCodeRequest req);

    void deleteCode(Long carrierId, Long codeId);
}
