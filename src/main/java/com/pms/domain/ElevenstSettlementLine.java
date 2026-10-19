package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 11st settlement line extension — the 11st mirror of one {@link SettlementLine} (FEATURE_2610_20 / D3 · D16,
 * changeset 111).
 *
 * <p>One column per kept field of the 11st settlement list row (D16 — 46 fields; member id / name are never kept)
 * AND {@code raw} (the same kept fields as XML), same split as {@link CoupangSettlementLine}: the neutral line
 * holds only what every market has, and "which 11st item made the difference" is answered from these columns.</p>
 *
 * <p>1:1 with the neutral line. Written only by {@code ElevenstSettlementMirrorWriter}; overwritten on every load.</p>
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → these @Column definitions must match changeset 111-1. {@code raw} is a CLOB
 * column mapped as LONGVARCHAR, same as {@link ElevenstOrderLine#getRaw()}.</p>
 */
@Entity
@Table(name = "elevenst_settlement_line",
        uniqueConstraints = @UniqueConstraint(name = "uq_elevenst_settlement_line",
                columnNames = "settlement_line_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ElevenstSettlementLine extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension. Hibernate sets it on INSERT and filters SELECTs — never set it by hand.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_line_id", nullable = false)
    private SettlementLine settlementLine;

    /** Lets the mirror be read per account without joining through the neutral line (same as CoupangSettlementLine). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;


    /** 11st order number ({@code ordNo}) — same value as {@code settlement_line.external_order_id}. */
    @Column(name = "ord_no", length = 100)
    private String ordNo;

    /** 11st order line sequence ({@code ordPrdSeq}) — joins the 11st order mirror (D5). */
    @Column(name = "ord_prd_seq", length = 50)
    private String ordPrdSeq;

    @Column(name = "dlv_no", length = 100)
    private String dlvNo;

    /** Claim number. Present = a refund row (D33). */
    @Column(name = "clm_req_seq", length = 100)
    private String clmReqSeq;

    @Column(name = "prd_no", length = 100)
    private String prdNo;

    @Column(name = "seller_prd_no", length = 100)
    private String sellerPrdNo;

    @Column(name = "prd_nm", length = 500)
    private String prdNm;

    @Column(name = "slct_prd_opt_nm", length = 500)
    private String slctPrdOptNm;

    @Column(name = "ord_qty", length = 20)
    private String ordQty;

    @Column(name = "fee_type_nm", length = 100)
    private String feeTypeNm;

    @Column(name = "ord_stl_end_dt")
    private LocalDate ordStlEndDt;

    /** Purchase-confirmed date = the neutral line's recognition date (D27). */
    @Column(name = "pocnfrm_dt")
    private LocalDate pocnfrmDt;

    @Column(name = "snd_end_dt")
    private LocalDate sndEndDt;

    @Column(name = "trt_end_dt")
    private LocalDate trtEndDt;

    @Column(name = "stl_dy")
    private LocalDate stlDy;

    /** 11st remittance date. ElevenstPayoutBuilder groups payouts by this date (D29). */
    @Column(name = "stl_pln_dy")
    private LocalDate stlPlnDy;

    @Column(name = "sel_prc", precision = 15, scale = 2)
    private BigDecimal selPrc;

    @Column(name = "opt_amt", precision = 15, scale = 2)
    private BigDecimal optAmt;

    @Column(name = "sel_prc_amt", precision = 15, scale = 2)
    private BigDecimal selPrcAmt;

    @Column(name = "sel_fee", precision = 15, scale = 2)
    private BigDecimal selFee;

    /** Base service-fee rate as 11st writes it (e.g. {@code 12.00%}) — kept as text. */
    @Column(name = "sel_fixed_fee", length = 20)
    private String selFixedFee;

    @Column(name = "deduct_amt", precision = 15, scale = 2)
    private BigDecimal deductAmt;

    @Column(name = "stl_amt", precision = 15, scale = 2)
    private BigDecimal stlAmt;

    @Column(name = "cupn_amt", precision = 15, scale = 2)
    private BigDecimal cupnAmt;

    @Column(name = "seller_cupn_amt", precision = 15, scale = 2)
    private BigDecimal sellerCupnAmt;

    @Column(name = "tmall_apply_dsc_amt", precision = 15, scale = 2)
    private BigDecimal tmallApplyDscAmt;

    @Column(name = "card_dsc_seller_dfrm_amt", precision = 15, scale = 2)
    private BigDecimal cardDscSellerDfrmAmt;

    @Column(name = "seller_dfrm_multi_dsc_cst", precision = 15, scale = 2)
    private BigDecimal sellerDfrmMultiDscCst;

    @Column(name = "sbdsc_seller_dfrm_amt_sum", precision = 15, scale = 2)
    private BigDecimal sbdscSellerDfrmAmtSum;

    @Column(name = "sbdsc_flctn_stl_amt", precision = 15, scale = 2)
    private BigDecimal sbdscFlctnStlAmt;

    @Column(name = "tmall_over_dsc_amt", precision = 15, scale = 2)
    private BigDecimal tmallOverDscAmt;

    @Column(name = "dlv_amt", precision = 15, scale = 2)
    private BigDecimal dlvAmt;

    @Column(name = "bm_stl_dlv_cst", precision = 15, scale = 2)
    private BigDecimal bmStlDlvCst;

    @Column(name = "clm_dlv_cst", precision = 15, scale = 2)
    private BigDecimal clmDlvCst;

    @Column(name = "add_clm_dlv_cst", precision = 15, scale = 2)
    private BigDecimal addClmDlvCst;

    @Column(name = "clm_fst_dlv_cst", precision = 15, scale = 2)
    private BigDecimal clmFstDlvCst;

    @Column(name = "bm_clm_fst_dlv_cst", precision = 15, scale = 2)
    private BigDecimal bmClmFstDlvCst;

    @Column(name = "abrd_cn_dlv_cst", precision = 15, scale = 2)
    private BigDecimal abrdCnDlvCst;

    @Column(name = "seller_dfrm_app_dlv_amt", precision = 15, scale = 2)
    private BigDecimal sellerDfrmAppDlvAmt;

    @Column(name = "seller_dfrm_deferred_ad_fee", precision = 15, scale = 2)
    private BigDecimal sellerDfrmDeferredAdFee;

    @Column(name = "seller_dfrm_intfree_fee", precision = 15, scale = 2)
    private BigDecimal sellerDfrmIntfreeFee;

    @Column(name = "seller_dfrm_ocb_amt", precision = 15, scale = 2)
    private BigDecimal sellerDfrmOcbAmt;

    @Column(name = "seller_dfrm_pnt_prd", precision = 15, scale = 2)
    private BigDecimal sellerDfrmPntPrd;

    @Column(name = "seller_dfrm_gbl_dlv_fee", precision = 15, scale = 2)
    private BigDecimal sellerDfrmGblDlvFee;

    @Column(name = "seller_dfrm_gbl_cn_dlv_cst", precision = 15, scale = 2)
    private BigDecimal sellerDfrmGblCnDlvCst;

    @Column(name = "deba_gtn_stl_amt", precision = 15, scale = 2)
    private BigDecimal debaGtnStlAmt;

    /** The kept fields as {@code <settlement><field>value</field>…</settlement>} (D16). Overwritten on every load. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "raw")
    private String raw;
}
