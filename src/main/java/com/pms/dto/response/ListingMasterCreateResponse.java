package com.pms.dto.response;

import com.pms.domain.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 마스터 프로덕트를 만든 결과. 프론트는 {@code masterProductId} 로 마스터 상세로 이동한다.
 *
 * <p>🔴 2609_71/D9 이후 이 DTO 를 쓰는 경로는 <b>「마켓 상품 id 로 마스터 만들기」</b>
 * ({@code MasterFromChannelService}) 하나뿐이다 — 셀 구성품 사본을 재료로 쓰던 옛 승격 경로(2609_22/04)는
 * 사라졌다. {@code cellStatus} 만 그 경로의 잔재로 null 을 허용한다.</p>
 */
@Getter
@Builder(toBuilder = true)
@Schema(description = "Result of creating a master product from a channel cell")
public class ListingMasterCreateResponse {

    @Schema(description = "Created master product ID", example = "88")
    private Long masterProductId;

    @Schema(description = "The channel cell that was linked", example = "312")
    private Long productListingId;

    @Schema(description = "Number of cell options linked to the new master options", example = "2")
    private int optionCount;

    /** 2609_45: 마켓에서 읽어온 셀의 상태. 기존 셀을 재사용한 경우에는 채우지 않는다(null). */
    @Schema(description = "The channel cell's status; null when created from an existing cell", example = "SELLING")
    private ListingStatus status;

    /**
     * 2609_47: 생성 직후 자동생성(썸네일·상세) 성공 여부. 화면은 사진을 매핑한 뒤 {@code regenerate} 를 한 번 더
     * 부르므로(D7), 이 값은 <b>사진을 하나도 매핑하지 않은 호출</b>에서 "재생성이 필요한가"를 가르는 데 쓴다.
     * 기존 셀을 재사용한 경우에는 채우지 않는다(null).
     */
    @Schema(description = "Whether the channel cell's assets were generated", example = "true")
    private Boolean assetsGenerated;
}
