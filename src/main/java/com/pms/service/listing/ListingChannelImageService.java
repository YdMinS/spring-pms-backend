package com.pms.service.listing;

import com.pms.dto.response.ChannelImageResponse;

/**
 * 이미 연결된 채널 셀의 마켓 이미지 조회(온보딩, 2026-09-19).
 *
 * <p><b>왜 따로 있나.</b> 마켓 이미지를 내려주는 창구는 지금까지 <b>가져오기 미리보기 2곳</b>뿐이었고
 * ({@code POST .../from-channel/preview}, {@code POST .../listings/import/preview}) 둘 다
 * 중복 가드에 걸린다. 그 가드는 "한 쿠팡 상품 = 한 셀" 을 지키는 <b>제 일을 하고 있으므로 건드리지
 * 않는다</b> — 대신 <b>이미 연결된 셀</b>을 대상으로 하는 이 경로를 따로 둔다. 이미 연결된 셀에는 애초에
 * 중복 검사가 필요 없다(중복은 이미 해소된 상태다).</p>
 *
 * <p>⚠️ <b>2609_63 이후 그 가드는 셀 존재 여부가 아니라 마스터 연결 여부를 본다</b>
 * ({@code existsByPlatformProductId} → {@code findByPlatformProductId}): 연결이 끊긴 셀이면 가져오기가
 * 그 행을 <b>재사용</b>한다. <b>이미 연결된 셀은 여전히 400 이므로 이 경로가 필요한 이유는 그대로다.</b></p>
 *
 * <p>🔴 <b>조회 전용</b>: 저장 0회. 쿠팡에는 {@link ListingChannel#fetchProduct} GET 1회만 나간다
 * (등록·수정·가격변경 없음). 이미지는 <b>URL 로만</b> 돌려준다 — 내려받지도, 우리 저장소로 옮기지도 않는다.</p>
 */
public interface ListingChannelImageService {

    /**
     * 셀의 {@code platformProductId} 로 마켓을 읽어 이미지 URL 두 목록을 돌려준다.
     *
     * <p>썸네일(마켓 가공본)과 상세(원본에 가까움)는 <b>분리된 목록</b>으로 원본 순서 그대로 나간다.
     * 이미지가 없는 응답은 예외가 아니라 <b>빈 목록</b>이다.</p>
     *
     * @param listingId 우리 셀 id (이미 마켓에 등록되어 {@code platformProductId} 를 가진 셀)
     * @return 썸네일·상세 이미지 URL
     * @throws com.pms.exception.ResourceNotFoundException 셀 없음(다른 테넌트 id 포함) · 계정 없음
     * @throws com.pms.exception.ValidationException       미지원 플랫폼 · {@code platformProductId} 없음 ·
     *                                                     비활성 계정 · 마켓 조회 실패 (400)
     * @throws com.pms.exception.CoupangRateLimitedException 429 쿨다운 창이 열려 있음 (그대로 전파된다)
     */
    ChannelImageResponse images(Long listingId);
}
