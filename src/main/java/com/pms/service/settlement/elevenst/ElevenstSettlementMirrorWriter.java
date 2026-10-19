package com.pms.service.settlement.elevenst;

import com.pms.domain.ElevenstSettlementLine;
import com.pms.domain.Platform;
import com.pms.domain.SettlementLine;
import com.pms.repository.ElevenstSettlementLineRepository;
import com.pms.service.settlement.SettlementMirrorWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.pms.service.settlement.elevenst.ElevenstSettlementRow.date;
import static com.pms.service.settlement.elevenst.ElevenstSettlementRow.money;
import static com.pms.service.settlement.elevenst.ElevenstSettlementRow.text;

/**
 * Writes the 11st mirror row of a stored neutral line (FEATURE_2610_20 / D3 · D16).
 *
 * <p>The raw value is the kept-field map {@link ElevenstSettlementSource} put on the draft
 * ({@link ElevenstSettlementRow#keep}); anything else is ignored. Joins the caller's transaction
 * ({@code SettlementLineUpserter}, REQUIRES_NEW) so the neutral line and the mirror commit together.</p>
 */
@Component
@RequiredArgsConstructor
public class ElevenstSettlementMirrorWriter implements SettlementMirrorWriter {

    private final ElevenstSettlementLineRepository elevenstSettlementLineRepository;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(SettlementLine line, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, String> row = (Map<String, String>) map;
        ElevenstSettlementLine existing = elevenstSettlementLineRepository
                .findBySettlementLine_Id(line.getId())
                .orElse(null);
        ElevenstSettlementLine.ElevenstSettlementLineBuilder builder =
                existing == null ? ElevenstSettlementLine.builder() : existing.toBuilder();

        elevenstSettlementLineRepository.save(builder
                .settlementLine(line)
                .marketplaceAccount(line.getMarketplaceAccount())
                .ordNo(text(row, "ordNo", 100))
                .ordPrdSeq(text(row, "ordPrdSeq", 50))
                .dlvNo(text(row, "dlvNo", 100))
                .clmReqSeq(text(row, "clmReqSeq", 100))
                .prdNo(text(row, "prdNo", 100))
                .sellerPrdNo(text(row, "sellerPrdNo", 100))
                .prdNm(text(row, "prdNm", 500))
                .slctPrdOptNm(text(row, "slctPrdOptNm", 500))
                .ordQty(text(row, "ordQty", 20))
                .feeTypeNm(text(row, "feeTypeNm", 100))
                .ordStlEndDt(date(row, "ordStlEndDt"))
                .pocnfrmDt(date(row, "pocnfrmDt"))
                .sndEndDt(date(row, "sndEndDt"))
                .trtEndDt(date(row, "trtEndDt"))
                .stlDy(date(row, "stlDy"))
                .stlPlnDy(date(row, "stlPlnDy"))
                .selPrc(money(row, "selPrc"))
                .optAmt(money(row, "optAmt"))
                .selPrcAmt(money(row, "selPrcAmt"))
                .selFee(money(row, "selFee"))
                .selFixedFee(text(row, "selFixedFee", 20))
                .deductAmt(money(row, "deductAmt"))
                .stlAmt(money(row, "stlAmt"))
                .cupnAmt(money(row, "cupnAmt"))
                .sellerCupnAmt(money(row, "sellerCupnAmt"))
                .tmallApplyDscAmt(money(row, "tmallApplyDscAmt"))
                .cardDscSellerDfrmAmt(money(row, "cardDscSellerDfrmAmt"))
                .sellerDfrmMultiDscCst(money(row, "sellerDfrmMultiDscCst"))
                .sbdscSellerDfrmAmtSum(money(row, "sbdscSellerDfrmAmtSum"))
                .sbdscFlctnStlAmt(money(row, "sbdscFlctnStlAmt"))
                .tmallOverDscAmt(money(row, "tmallOverDscAmt"))
                .dlvAmt(money(row, "dlvAmt"))
                .bmStlDlvCst(money(row, "bmStlDlvCst"))
                .clmDlvCst(money(row, "clmDlvCst"))
                .addClmDlvCst(money(row, "addClmDlvCst"))
                .clmFstDlvCst(money(row, "clmFstDlvCst"))
                .bmClmFstDlvCst(money(row, "bmClmFstDlvCst"))
                .abrdCnDlvCst(money(row, "abrdCnDlvCst"))
                .sellerDfrmAppDlvAmt(money(row, "sellerDfrmAppDlvAmt"))
                .sellerDfrmDeferredAdFee(money(row, "sellerDfrmDeferredAdFee"))
                .sellerDfrmIntfreeFee(money(row, "sellerDfrmIntfreeFee"))
                .sellerDfrmOcbAmt(money(row, "sellerDfrmOcbAmt"))
                .sellerDfrmPntPrd(money(row, "sellerDfrmPntPrd"))
                .sellerDfrmGblDlvFee(money(row, "sellerDfrmGblDlvFee"))
                .sellerDfrmGblCnDlvCst(money(row, "sellerDfrmGblCnDlvCst"))
                .debaGtnStlAmt(money(row, "debaGtnStlAmt"))
                .raw(ElevenstSettlementRow.rawXml(row))
                .build());
    }
}
