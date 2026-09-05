package com.pms.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 셀 상세 템플릿 지정(2609_20/D5). {@code templateId=null} 은 "기본값으로 복귀"(계정 ?? 테넌트 기본)라
 * {@code @NotNull} 을 붙이지 않는다(D6).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DetailTemplateSelectRequest {

    private Long templateId;
}
