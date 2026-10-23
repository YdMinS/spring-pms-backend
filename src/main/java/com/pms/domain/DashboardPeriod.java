package com.pms.domain;

/**
 * Period a dashboard card reads (FEATURE_2610_29 D22 · D23 · FEATURE_2610_30 D38). {@code TODAY} = today,
 * {@code DAYS_7} = today − 6 days ~ today, {@code DAYS_30} = today − 29 days ~ today, {@code THIS_MONTH} = the 1st of
 * this month ~ today, {@code LAST_MONTH} = the 1st ~ the last day of last month. The screen turns the period into
 * dates; the server checks which card may use which period ({@link DashboardCardType#allows}).
 */
public enum DashboardPeriod {
    TODAY,
    DAYS_7,
    DAYS_30,
    THIS_MONTH,
    LAST_MONTH
}
