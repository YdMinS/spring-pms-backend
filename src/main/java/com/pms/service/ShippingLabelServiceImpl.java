package com.pms.service;

import com.pms.domain.InternalShipmentStage;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.request.ShippingLabelExportRequest.ExportRow;
import com.pms.dto.response.InternalLabelPreview;
import com.pms.dto.response.ShippingLabelPreviewRow;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ShippingLabelService} implementation — each marketplace's {@link ShippingLabelAdapter} reads its rows
 * (FEATURE_2610_23 / D21: Coupang ordersheets INSTRUCT · 11st 1635) → xlsx.
 *
 * Fetched rows are upserted into the order tables inside the adapters (PLAN 2609_13 D1) — best-effort, so a failed
 * save still returns the sheet (D6). Never put @Transactional on this service (D3). Account and seller are fetched
 * eagerly by the repository's {@code @EntityGraph}, so this service, which loops over external HTTP, reads
 * seller.sellerName safely without @Transactional (open-in-view=false).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingLabelServiceImpl implements ShippingLabelService {

    private static final String[] HEADERS = {
            "받는사람 이름", "전화번호", "우편번호", "주소", "상품명", "택배수량",
            "내품수량", "주문번호", "배송메시지", "관리코드", "판매자", "플랫폼"
    };

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final OrderLineRepository orderLineRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    /** FEATURE_2610_23 / D21: one sheet-row reader per marketplace. */
    private final List<ShippingLabelAdapter> shippingLabelAdapters;
    /** FEATURE_2610_23 / D23 · D24: the marketplaces that have the 「내부 상품준비중」 sheet. */
    private final InternalStageMarkets internalStageMarkets;

    public List<ShippingLabelRow> collectRows(Long sellerId) {
        List<MarketplaceAccount> accounts = (sellerId == null)
                ? marketplaceAccountRepository.findByIsActiveTrue()
                : marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(sellerId);

        List<ShippingLabelRow> rows = new ArrayList<>();
        int targetAccounts = 0;
        int failedAccounts = 0;
        for (MarketplaceAccount account : accounts) {
            Optional<ShippingLabelAdapter> adapter = adapterFor(account.getPlatform());
            if (adapter.isEmpty()) {
                continue;
            }
            targetAccounts++;
            try {
                rows.addAll(adapter.get().collectRows(account));
            } catch (Exception e) {
                // 한 계정 오류가 전체를 막지 않는다 — 로그 후 계속.
                failedAccounts++;
                log.warn("송장 접수시트 계정 조회 실패: account={} platform={}",
                        account.getId(), account.getPlatform(), e);
            }
        }
        // When every target account (any marketplace with an adapter) failed and not one row was collected, surface
        // the error instead of an empty file (the controller maps it to 500 — tells "fine, 0 preparing orders" from
        // "lookup failed").
        if (rows.isEmpty() && targetAccounts > 0 && failedAccounts == targetAccounts) {
            throw new IllegalStateException("쿠팡 ordersheets 조회 실패 — 대상 계정 전체 오류");
        }
        return rows;
    }

    @Override
    public List<ShippingLabelPreviewRow> previewRows(Long sellerId) {
        return collectRows(sellerId).stream().map(ShippingLabelPreviewRow::from).toList();
    }

    @Override
    public List<ShippingLabelPreviewRow> previewRowsByOrder(Long orderItemId) {
        OrderLine line = orderLineRepository.findWithAccountAndSellerById(orderItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderItemId));

        String externalOrderId = line.getOrder().getExternalOrderId();
        MarketplaceAccount account = line.getOrder().getMarketplaceAccount();
        ShippingLabelAdapter adapter = adapterFor(account.getPlatform())
                .orElseThrow(() -> new IllegalArgumentException(
                        "쿠팡 · 11번가 주문만 송장시트를 만들 수 있습니다: " + account.getPlatform()));
        return adapter.collectOrderRows(account, externalOrderId, orderItemId).stream()
                .map(ShippingLabelPreviewRow::from).toList();
    }

    @Override
    public InternalLabelPreview previewInternalRows(Long sellerId) {
        List<MarketplaceAccount> accounts = (sellerId == null)
                ? marketplaceAccountRepository.findByIsActiveTrue()
                : marketplaceAccountRepository.findBySeller_IdAndIsActiveTrue(sellerId);

        List<ShippingLabelRow> rows = new ArrayList<>();
        Set<String> notAccepted = new LinkedHashSet<>();
        int targetAccounts = 0;
        int failedAccounts = 0;
        for (MarketplaceAccount account : accounts) {
            Optional<ShippingLabelAdapter> adapter = adapterFor(account.getPlatform());
            if (adapter.isEmpty() || !internalStageMarkets.supports(account.getPlatform())) {
                continue;
            }
            List<OrderShipment> internal = orderShipmentRepository.findByInternalStageAndAccountWithLineStatus(
                    InternalShipmentStage.INTERNAL_PREPARING, account.getId(), OrderStatus.PAID);   // D29
            if (internal.isEmpty()) {
                continue;                                  // no target → no marketplace call for this account
            }
            targetAccounts++;
            try {
                Set<String> found = adapter.get().collectInternalRows(account, internal, rows);
                for (OrderShipment shipment : internal) {
                    if (!found.contains(shipment.getExternalShipmentId())) {
                        notAccepted.add(shipment.getOrder().getExternalOrderId());
                    }
                }
            } catch (Exception e) {
                failedAccounts++;
                log.warn("내부 접수시트 계정 조회 실패: account={}", account.getId(), e);
            }
        }
        if (rows.isEmpty() && targetAccounts > 0 && failedAccounts == targetAccounts) {
            throw new IllegalStateException("쿠팡 ordersheets 조회 실패 — 대상 계정 전체 오류");
        }
        return new InternalLabelPreview(rows.stream().map(ShippingLabelPreviewRow::from).toList(),
                List.copyOf(notAccepted));
    }

    /**
     * 편집된 export rows 를 xlsx bytes 로 변환한다.
     *
     * <p>편집분 재매칭 없이 posted rows 그대로 {@link #toXlsx}로 넘긴다(비대칭 의도 — rowKey 는
     * xlsx 미출력이라 vendorItemId 는 ""로 채운다).
     */
    @Override
    public byte[] toXlsxFromExport(List<ExportRow> rows) {
        List<ShippingLabelRow> mapped = rows.stream()
                .map(e -> new ShippingLabelRow(
                        e.receiverName(), e.receiverPhone(), e.postCode(), e.address(),
                        e.productName(), e.quantity(),
                        e.parcelQuantity(), "",
                        e.orderId(), e.deliveryMessage(), e.shipmentBoxId(),
                        e.sellerName(), e.platform()))
                .toList();
        return toXlsx(mapped);
    }

    /** The adapter whose platform matches — same lookup as {@code ClaimActionServiceImpl.resolve()}. */
    private Optional<ShippingLabelAdapter> adapterFor(Platform platform) {
        return shippingLabelAdapters.stream().filter(adapter -> adapter.platform() == platform).findFirst();
    }

    public byte[] toXlsx(List<ShippingLabelRow> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("송장접수");

            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) {
                header.createCell(c).setCellValue(HEADERS[c]);
            }

            int r = 1;
            for (ShippingLabelRow row : rows) {
                Row dataRow = sheet.createRow(r++);
                dataRow.createCell(0).setCellValue(row.receiverName());
                dataRow.createCell(1).setCellValue(row.receiverPhone());
                dataRow.createCell(2).setCellValue(row.postCode());
                dataRow.createCell(3).setCellValue(row.address());
                dataRow.createCell(4).setCellValue(row.productName());
                // 택배수량(박스·라벨 수) — 기본 1이며 V2 편집 시 사용자 조정값이 반영된다.
                // 실제 주문 개수는 내품수량에만 노출(한 송장에 "N개" 표기).
                dataRow.createCell(5).setCellValue(row.parcelQuantity());     // 택배수량
                dataRow.createCell(6).setCellValue(row.quantity());          // 내품수량(주문 개수)
                dataRow.createCell(7).setCellValue(row.orderId());
                dataRow.createCell(8).setCellValue(row.deliveryMessage());
                dataRow.createCell(9).setCellValue(row.shipmentBoxId());     // 관리코드(업로드 레그 매칭 키)
                dataRow.createCell(10).setCellValue(row.sellerName());
                dataRow.createCell(11).setCellValue(row.platform());
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("송장 접수시트 xlsx 생성 실패", e);
        }
    }
}
