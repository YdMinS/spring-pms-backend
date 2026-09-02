package com.pms.service.coupang;

import com.pms.dto.response.SyncTargetResponse;

import java.util.List;

/**
 * 동기화 대상 채널 목록 조회 (GET /api/orders/sync/targets, FEATURE_2609_02 / PLAN D2).
 *
 * 클라이언트가 계정 단위로 분할 동기화하고 진행률을 표시하기 위한 read 전용 서비스.
 */
public interface SyncTargetService {

    /** 동기화 대상 목록. sellerId 가 null 이면 전체, 있으면 해당 셀러만. */
    List<SyncTargetResponse> list(Long sellerId);
}
