package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 마켓 이미지 URL 을 이 물품의 갤러리로 복제한다(2609_67/D5 — 물품 등록 참고 패널에서 고른 사진).
 *
 * <p>서버가 URL 을 내려받아 우리 저장소에 넣는다 — <b>URL 을 그대로 저장하지 않는다</b>. 🔴 사용자가 보낸 값을
 * 서버가 그대로 치므로 {@code https} + 마켓 이미지 호스트({@code *.coupangcdn.com})만 허용하고 한 번에 10장까지다
 * (서비스가 강제, 위반은 400).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Marketplace image URLs to download into this product's gallery")
public class ProductImageFromUrlRequest {

    @NotEmpty(message = "가져올 이미지가 없습니다")
    @Schema(description = "Marketplace image URLs (https, *.coupangcdn.com, max 10), in append order")
    private List<@NotBlank String> urls;
}
