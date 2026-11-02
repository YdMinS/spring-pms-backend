package com.pms.domain;

/**
 * 실물 박스의 상태 (PLAN 2609_40 D1 · D32).
 *
 * <p>값을 정의하는 것은 이 조각(01)이고, {@link #PACKED}/{@link #UNUSED} 로 옮기는 것은 포장 콘솔(03)이다.
 */
public enum ParcelStatus {

    /** 송장은 발급됐지만 아직 싸지 않은 박스. 송장이 저장되는 순간의 상태다. */
    PENDING,

    /** 싸서 출고까지 끝난 박스. 🔴 동기화 백필이 이 상태를 덮지 않는다(원장과 화면이 갈라진다). */
    PACKED,

    /**
     * A box whose label ended up not being used (D32).
     *
     * <p>The carrier sometimes issues two labels for a bundle that fits in one box — this is the door that closes
     * the box left over. It leaves no stock out, no box recipe and no savings. It can be restored by scanning it
     * again on the packing screen, which puts it back to {@link #PENDING} (PLAN 2611_03 D1 · D12:
     * 「포장 화면에서 다시 스캔해 되돌릴 수 있다(되돌리면 작업 전으로)」).
     */
    UNUSED
}
