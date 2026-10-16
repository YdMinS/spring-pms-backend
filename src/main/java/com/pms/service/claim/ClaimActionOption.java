package com.pms.service.claim;

import com.pms.domain.ClaimAction;

import java.util.List;

/**
 * One "action you can press on this claim now" (FEATURE_2609_21 / PLAN D1).
 *
 * <p>The server sends it and the UI only renders it. Once the front end starts branching on
 * {@code if (platform === 'COUPANG')}, adding Naver means rewriting three screens.
 *
 * @param label        display name — <b>filled by the adapter</b> (D18)
 * @param requires     extra input kind. A client renders no button for a value it does not know
 * @param choices      filled only when {@code requires} asks to pick a value (D19). Empty → the UI draws no choices
 * @param irreversible whether the action cannot be undone — the reason for the UI's 2-step confirm (D10)
 * @param hint         one gray line under the input, or null (FEATURE_2610_17 / D34 · D39 ④ — 11st reject /
 *                     completion hold)
 */
public record ClaimActionOption(ClaimAction action,
                                String label,
                                ClaimAction.Requires requires,
                                List<ActionChoice> choices,
                                boolean irreversible,
                                String hint) {
}
