package com.pms.service.packing;

import com.pms.service.coupang.SyncWindow;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Parcel time display — the one UTC → KST turn of stored parcel times (FEATURE_2611_03 / PLAN D40 · FEATURE_2611_07 /
 * PLAN D38).
 *
 * <p>Parcel and parcel history times are stored as the server's UTC wall clock; the packing history, 「오늘 완료」 and
 * the order detail parcel chips show them as KST. Every one of them calls {@link #utcToKst} — do not write the
 * conversion again.
 */
public final class ParcelTimes {

    private ParcelTimes() {
    }

    /** A stored UTC wall-clock time as a KST wall-clock time, no zone in the value. {@code null} stays {@code null}. */
    public static LocalDateTime utcToKst(LocalDateTime utc) {
        return utc == null ? null : utc.atZone(ZoneOffset.UTC).withZoneSameInstant(SyncWindow.KST).toLocalDateTime();
    }
}
