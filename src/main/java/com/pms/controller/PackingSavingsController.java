package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.PackingSavingsBoxRow;
import com.pms.dto.response.PackingSavingsOptionRow;
import com.pms.dto.response.PackingSavingsSummary;
import com.pms.service.packing.PackingSavingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 포장 절약 조회 API — <b>ADMIN 전용</b> (FEATURE_2609_41 / PLAN 2609_41 S7 · S10 · S13 · S16).
 *
 * <p>손익과 같은 등급의 경영 데이터라 {@code SalesStatsController} 의 관례를 그대로 따른다:
 * 기간 기본값은 {@code to}=오늘 · {@code from}=이번 달 1일, {@code from > to} 면 400
 * ({@code IllegalArgumentException} → {@code GlobalExceptionHandler}, 여기서 다시 잡지 않는다).
 *
 * <p>🔴 <b>기준일이 매출 화면과 다르다</b>: 여기는 <b>포장 완료일</b>({@code packed_at})이고 매출은 주문일이다(S7).
 * 화면이 이 차이를 한 줄로 밝혀야 한다.
 *
 * <p>🔴 <b>축은 판매자·기간·옵션·상자뿐이다</b>(S13) — 채널 파라미터를 더하지 말 것. 포장은 창고 행위라
 * 채널과 무관하고, 축을 늘리면 "채널별 절약"이라는 의미 없는 숫자가 생긴다.
 *
 * <p>전부 로컬 DB 집계다 — 마켓을 부르지 않으므로 화면이 자주 열려도 API 호출량이 되지 않는다.
 */
@RestController
@RequestMapping("/api/admin/packing/savings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PackingSavingsController {

    private final PackingSavingsService packingSavingsService;

    /**
     * ① 전체 합계 + 재활용·합포장·제외 건수.
     *
     * <p>🔴 {@code recycledSaving} 과 {@code consolidatedSaving} 은 겹칠 수 있어 <b>더하면 전체보다 커진다</b>.
     * 🔴 {@code missingBasisCount}(박스가 통째로 빠짐)와 {@code missingShippingFeeCount}(택배 절약만 빠짐)는
     * 다른 숫자다.
     */
    @GetMapping("/summary")
    public ResponseEntity<ResponseDTO<PackingSavingsSummary>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(packingSavingsService.summary(from, to, sellerId)));
    }

    /** ② 옵션별 — 행의 키는 <b>마스터 옵션</b>이고 합계는 ① 과 정확히 일치한다(S5 · S16). */
    @GetMapping("/options")
    public ResponseEntity<ResponseDTO<List<PackingSavingsOptionRow>>> options(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(packingSavingsService.byOption(from, to, sellerId)));
    }

    /** ③ 상자별 — 치수·사진을 함께 내린다(화면이 상자 그림을 그린다, 2609_40 / 04). */
    @GetMapping("/boxes")
    public ResponseEntity<ResponseDTO<List<PackingSavingsBoxRow>>> boxes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(packingSavingsService.byBox(from, to, sellerId)));
    }
}
