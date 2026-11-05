package com.pms.service;

import com.pms.domain.Product;
import com.pms.dto.request.MergeProductsRequest;
import com.pms.dto.response.MergePreviewResponse;

import java.util.List;
import java.util.Map;

/**
 * The master side of a product merge (FEATURE_2611_09): the discarded product's master links move to the kept
 * product with every merge (D1 · D2).
 *
 * <p>Two kinds of master hold the discarded product:</p>
 * <ul>
 *   <li><b>replaced</b> — its component set stays unique once the discarded product is replaced by the kept one:
 *       the component row and option quantity rows are rewritten in place (D3 ·
 *       {@link MasterProductService#replaceComponentProduct}).</li>
 *   <li><b>deleted</b> — its component set would equal another master's ("one master per component set",
 *       2609_46): its listings move to that other master, which already holds the kept product (D5 · D16 · D17),
 *       each option going to the surviving option the operator picked (D18 · D23 · D24), and the master is then
 *       deleted through {@code MasterDeleteService} (D20).</li>
 * </ul>
 *
 * <p>🔴 Every refusal (D4 · FEATURE_2611_13 D2 · D27 · D28 · D24 ⑤) is raised by {@link #plan}, before the merge
 * moves anything (D6). {@link #deleteEmptiedMasters} stays the merge's last step before the product delete (D20);
 * the edited S3 photos it removes are deleted only after the commit (FEATURE_2611_13 D6), so a rollback keeps them.</p>
 */
public interface MergeMasterLinkService {

    /**
     * Read-only preview for the merge screen (D28). Same refusals as {@link #plan} except the option choices.
     *
     * @throws com.pms.exception.ResourceNotFoundException either product is not found (404)
     * @throws com.pms.exception.BusinessException         409 for D4 / FEATURE_2611_13 D2 / D27, 400 for the same
     *                                                     product twice
     */
    MergePreviewResponse preview(Long targetProductId, Long sourceProductId);

    /**
     * Decide what the merge does to the masters and validate the operator's choices. Writes nothing.
     *
     * @param choices the request's {@code masterOptionChoices} (null = empty)
     * @throws com.pms.exception.BusinessException 409 for D4 / FEATURE_2611_13 D2 / D27, 400 for a missing or
     *                                             foreign choice (D28) and
     *                                             for a new option name already taken (D24 ⑤)
     */
    Plan plan(Product target, Product source, List<MergeProductsRequest.MasterOptionChoice> choices);

    /**
     * Rewrite the replaced masters and move the listings of the deleted ones (D3 · D7 · D17 · D21 · D25).
     * The deleted masters are left empty but still exist — {@link #deleteEmptiedMasters} removes them.
     *
     * @return ids of the masters to regenerate after commit, ascending (replaced + receiving masters)
     */
    List<Long> moveLinks(Plan plan, Product target, Product source);

    /** Delete every master emptied by {@link #moveLinks} (D20). Call it right before the product delete. */
    void deleteEmptiedMasters(Plan plan);

    /**
     * @param sourceMasterCount every master holding the discarded product (D10 · D11 count)
     * @param replacedMasterIds masters whose component is rewritten, ascending
     * @param pairs             masters to delete, ascending by the deleted master's id
     */
    record Plan(int sourceMasterCount, List<Long> replacedMasterIds, List<Pair> pairs) {

        public List<Long> deletedMasterIds() {
            return pairs.stream().map(Pair::deleteMasterId).toList();
        }
    }

    /**
     * @param optionMoves deleted master option id → where it goes, ascending by key
     */
    record Pair(Long deleteMasterId, Long keepMasterId, Map<Long, OptionMove> optionMoves) {}

    /**
     * @param targetOptionId the surviving option, or null to create {@code newOptionName}
     * @param newVector      the deleted option's quantities with the kept product in place of the discarded one
     */
    record OptionMove(Long targetOptionId, String newOptionName, Map<Long, Integer> newVector) {}
}
