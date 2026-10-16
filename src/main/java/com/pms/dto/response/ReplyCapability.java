package com.pms.dto.response;

import com.pms.service.claim.ActionChoice;

import java.util.List;

/**
 * Whether a reply can be sent now, and its limits (FEATURE_2609_23 / PLAN D5 · 04 Step 2).
 *
 * Only the <b>single</b> inquiry response and the reply success response carry it (not the list — every row would
 * have to look into its account, and the list does not use it).
 *
 * <p>🔴 The judgement lives in one server place only ({@code InquiryReplyPolicy}). The reply rules differ per type
 * (product Q&A 1+ characters · call center 2+ · {@code parentAnswerId} required · the 11st non-delivery form), so a
 * client-side judgement would make the web and mobile copies drift. {@code reason} is also a <b>finished sentence</b>
 * from the server — a code→text map in the client would show the same reason differently per screen (same contract
 * as 2609_21 D18).
 *
 * @param canReply      whether a reply can be sent now
 * @param reason        <b>user-facing text</b>, filled only when {@code canReply=false}
 * @param minLength     minimum body length (after strip)
 * @param maxLength     maximum body length (after strip)
 * @param once          true = cannot be undone → the client asks a two-step confirm (D17)
 * @param parentReplyId call center only. Chosen by the server; the client only sends it back as is
 *                      (on sending, <b>the server picks its own value again</b> — it is not trusted)
 * @param form          which input the screen draws: {@value #FORM_TEXT} (the reply text) or
 *                      {@value #FORM_NON_DELIVERY} (11st non-delivery handling, FEATURE_2610_17 / D68)
 * @param reasons       {@value #FORM_NON_DELIVERY} only: the handling reasons (code · 11st's own name); empty otherwise
 */
public record ReplyCapability(
        boolean canReply,
        String reason,
        int minLength,
        int maxLength,
        boolean once,
        String parentReplyId,
        String form,
        List<ActionChoice> reasons) {

    public static final String FORM_TEXT = "TEXT";
    public static final String FORM_NON_DELIVERY = "NON_DELIVERY";

    /** The plain reply-text form. */
    public ReplyCapability(boolean canReply, String reason, int minLength, int maxLength, boolean once,
                           String parentReplyId) {
        this(canReply, reason, minLength, maxLength, once, parentReplyId, FORM_TEXT, List.of());
    }
}
