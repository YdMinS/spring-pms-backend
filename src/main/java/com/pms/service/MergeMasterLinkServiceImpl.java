package com.pms.service;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MergeProductsRequest;
import com.pms.dto.response.MasterOptionResponse;
import com.pms.dto.response.MasterProductByComponentsResponse;
import com.pms.dto.response.MergePreviewResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.service.listing.MasterDeleteService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * FEATURE_2611_09 — contract and step order: {@link MergeMasterLinkService}.
 *
 * <p>⚠️ No {@code @TenantId} on the component and option tables: masters are always resolved through the
 * tenant-scoped finders ({@code findScopedById} · {@code findScopedByIdIn}) — the products come in already scoped.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MergeMasterLinkServiceImpl implements MergeMasterLinkService {

    private final ProductRepository productRepository;
    private final MasterProductRepository masterProductRepository;
    private final MasterProductComponentRepository componentRepository;
    private final MasterProductOptionRepository optionRepository;
    private final MasterProductOptionItemRepository optionItemRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterProductService masterProductService;
    private final MasterDeleteService masterDeleteService;
    private final ListingAssetService listingAssetService;

    @Override
    public MergePreviewResponse preview(Long targetProductId, Long sourceProductId) {
        if (targetProductId.equals(sourceProductId)) {
            throw new BusinessException("같은 물품끼리는 병합할 수 없습니다.", HttpStatus.BAD_REQUEST);
        }
        Product target = requireProduct(targetProductId);
        Product source = requireProduct(sourceProductId);
        Layout layout = layout(target, source);

        List<MergePreviewResponse.MasterPair> pairs = new ArrayList<>();
        for (MasterPairEntities pair : layout.pairs()) {
            List<OptionData> keepOptions = loadOptions(pair.keep().getId());
            List<MergePreviewResponse.OptionRow> rows = new ArrayList<>();
            for (OptionData option : loadOptions(pair.delete().getId())) {
                Map<Long, Integer> vector = replaced(option.vector(), source.getId(), target.getId());
                rows.add(new MergePreviewResponse.OptionRow(option.option().getId(), option.option().getName(),
                        items(vector, option.names(), target),
                        defaultOptionId(option.option().getName(), vector, keepOptions)));
            }
            List<MergePreviewResponse.ListingRef> listings =
                    productListingRepository.findByMasterProductId(pair.delete().getId()).stream()
                            .sorted(Comparator.comparing(ProductListing::getId))
                            .map(l -> new MergePreviewResponse.ListingRef(l.getId(), l.getName(), l.getPlatform().name()))
                            .toList();
            pairs.add(new MergePreviewResponse.MasterPair(
                    pair.delete().getId(), pair.delete().getName(), pair.keep().getId(), pair.keep().getName(),
                    listings,
                    keepOptions.stream().map(o -> new MergePreviewResponse.OptionRef(o.option().getId(),
                            o.option().getName(), items(o.vector(), o.names(), null))).toList(),
                    rows));
        }
        return new MergePreviewResponse(pairs);
    }

    @Override
    public Plan plan(Product target, Product source, List<MergeProductsRequest.MasterOptionChoice> choices) {
        Layout layout = layout(target, source);
        Map<Long, MergeProductsRequest.MasterOptionChoice> choiceBySource = new LinkedHashMap<>();
        for (MergeProductsRequest.MasterOptionChoice choice : choices == null
                ? List.<MergeProductsRequest.MasterOptionChoice>of() : choices) {
            choiceBySource.putIfAbsent(choice.sourceOptionId(), choice);
        }

        List<Pair> pairs = new ArrayList<>();
        for (MasterPairEntities pair : layout.pairs()) {
            MasterProduct delete = pair.delete();
            MasterProduct keep = pair.keep();
            List<OptionData> keepOptions = loadOptions(keep.getId());
            Set<Long> keepOptionIds = keepOptions.stream().map(o -> o.option().getId()).collect(Collectors.toSet());
            Set<String> takenNames = keepOptions.stream().map(o -> trim(o.option().getName()))
                    .collect(Collectors.toCollection(HashSet::new));

            Map<Long, OptionMove> moves = new TreeMap<>();
            for (OptionData option : loadOptions(delete.getId())) {
                MergeProductsRequest.MasterOptionChoice choice = choiceBySource.get(option.option().getId());
                boolean hasTarget = choice != null && choice.targetOptionId() != null;
                boolean hasNewName = choice != null && choice.newOptionName() != null
                        && !choice.newOptionName().isBlank();
                if (!hasTarget && !hasNewName) {
                    throw new BusinessException("옵션 고르기가 빠졌습니다: " + label(delete)
                            + " 「" + option.option().getName() + "」", HttpStatus.BAD_REQUEST);
                }
                Map<Long, Integer> vector = replaced(option.vector(), source.getId(), target.getId());
                if (hasTarget) {
                    if (!keepOptionIds.contains(choice.targetOptionId())) {
                        throw new BusinessException("고른 옵션이 남는 마스터 " + label(keep) + " 의 옵션이 아닙니다: "
                                + choice.targetOptionId(), HttpStatus.BAD_REQUEST);
                    }
                    moves.put(option.option().getId(), new OptionMove(choice.targetOptionId(), null, vector));
                } else {
                    String name = choice.newOptionName().trim();
                    // D24 ⑤: option names are unique within a master — the new ones of this request included.
                    if (!takenNames.add(name)) {
                        throw new BusinessException("마스터 옵션명 중복: " + name, HttpStatus.BAD_REQUEST);
                    }
                    moves.put(option.option().getId(), new OptionMove(null, name, vector));
                }
            }
            pairs.add(new Pair(delete.getId(), keep.getId(), moves));
        }
        return new Plan(layout.sourceMasterCount(),
                layout.replaced().stream().map(MasterProduct::getId).toList(), pairs);
    }

    @Override
    @Transactional
    public List<Long> moveLinks(Plan plan, Product target, Product source) {
        Set<Long> changed = new TreeSet<>();
        for (Long masterId : plan.replacedMasterIds()) {
            masterProductService.replaceComponentProduct(masterId, source, target);
            changed.add(masterId);
        }
        for (Pair pair : plan.pairs()) {
            MasterProduct keep = masterProductRepository.findScopedById(pair.keepMasterId())
                    .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", pair.keepMasterId()));
            // 🔴 New options first (D25): created while the moving listings still hang under the deleted master,
            // so onOptionCreated adds the switched-off row to the surviving master's OWN listings only — the
            // moving listings get their row by the relink below.
            Map<Long, MasterProductOption> resolved = new LinkedHashMap<>();
            pair.optionMoves().forEach((sourceOptionId, move) -> resolved.put(sourceOptionId,
                    requireOption(move.targetOptionId() != null
                            ? move.targetOptionId()
                            : createOption(keep.getId(), move).getId())));

            List<ProductListing> listings = productListingRepository.findByMasterProductId(pair.deleteMasterId())
                    .stream().sorted(Comparator.comparing(ProductListing::getId)).toList();
            // One query for every moving listing's options — not one per listing.
            Map<Long, List<ProductListingOption>> optionsByListing = listings.isEmpty() ? Map.of()
                    : productListingOptionRepository.findByProductListingIdIn(
                            listings.stream().map(ProductListing::getId).toList()).stream()
                            .collect(Collectors.groupingBy(o -> o.getProductListing().getId()));
            for (ProductListing listing : listings) {
                for (ProductListingOption option : optionsByListing.getOrDefault(listing.getId(), List.of())) {
                    // D26: a channel-only option (no master option) stays channel-only.
                    MasterProductOption current = option.getMasterProductOption();
                    if (current != null && resolved.containsKey(current.getId())) {
                        productListingOptionRepository.save(
                                option.toBuilder().masterProductOption(resolved.get(current.getId())).build());
                    }
                }
                // D21: an on-market listing gets [수정 요청]; nothing is sent. Its own category code is untouched.
                ProductListing moved = productListingRepository.save(listing.toBuilder()
                        .masterProduct(keep)
                        .needsMarketSync(listing.isNeedsMarketSync() || listing.getPlatformProductId() != null)
                        .build());
                listingAssetService.recalculateOptionPrices(moved);
            }
            changed.add(keep.getId());
        }
        return new ArrayList<>(changed);
    }

    @Override
    @Transactional
    public void deleteEmptiedMasters(Plan plan) {
        for (Long masterId : plan.deletedMasterIds()) {
            masterDeleteService.deleteMaster(masterId);
        }
    }

    // ---------------------------------------------------------------- layout (D4 · D5 · D16 · D27)

    /**
     * Which masters of the discarded product are rewritten and which are deleted. Refuses a master holding both
     * products (D4) and a master that would equal two or more masters (D27).
     */
    private Layout layout(Product target, Product source) {
        List<Long> sourceMasterIds = masterIdsOf(source.getId());
        Set<Long> targetMasterIds = new HashSet<>(masterIdsOf(target.getId()));
        List<Long> both = sourceMasterIds.stream().filter(targetMasterIds::contains).toList();
        if (!both.isEmpty()) {
            throw new BusinessException("남길 물품과 버릴 물품이 함께 들어 있는 마스터 상품이 있습니다: "
                    + labels(scopedMasters(both)) + ". 마스터 구성 편집에서 한쪽을 먼저 빼 주세요.",
                    HttpStatus.CONFLICT);
        }

        List<MasterProduct> masters = scopedMasters(sourceMasterIds);
        Map<Long, List<MasterProductComponent>> componentsByMaster = masters.isEmpty() ? Map.of()
                : componentRepository.findWithProductByMasterProductIdIn(
                        masters.stream().map(MasterProduct::getId).toList()).stream()
                        .collect(Collectors.groupingBy(c -> c.getMasterProduct().getId()));

        List<MasterProduct> replaced = new ArrayList<>();
        List<MasterPairEntities> pairs = new ArrayList<>();
        for (MasterProduct master : masters) {
            List<Long> newSet = componentsByMaster.getOrDefault(master.getId(), List.of()).stream()
                    .map(c -> c.getProduct().getId())
                    .map(id -> id.equals(source.getId()) ? target.getId() : id)
                    .distinct().toList();
            List<Long> sameSet = masterProductService.findByComponents(newSet).stream()
                    .map(MasterProductByComponentsResponse::getId)
                    .filter(id -> !id.equals(master.getId()))
                    .sorted().toList();
            if (sameSet.size() >= 2) {
                List<MasterProduct> named = new ArrayList<>(List.of(master));
                named.addAll(scopedMasters(sameSet));
                throw new BusinessException("구성품이 같아지는 마스터 상품이 둘 이상 있습니다: " + labels(named)
                        + ". 마스터 구성 편집에서 먼저 정리해 주세요.", HttpStatus.CONFLICT);
            }
            if (sameSet.isEmpty()) {
                replaced.add(master);
            } else {
                pairs.add(new MasterPairEntities(master, masterProductRepository.findScopedById(sameSet.get(0))
                        .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", sameSet.get(0)))));
            }
        }
        return new Layout(masters.size(), replaced, pairs);
    }

    private List<Long> masterIdsOf(Long productId) {
        return componentRepository.findByProductId(productId).stream()
                .map(c -> c.getMasterProduct().getId())
                .distinct().sorted().toList();
    }

    private List<MasterProduct> scopedMasters(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return masterProductRepository.findScopedByIdIn(ids).stream()
                .sorted(Comparator.comparing(MasterProduct::getId)).toList();
    }

    // ---------------------------------------------------------------- options (D23 · D24)

    /** Every option of a master with its quantities and product names, ascending by id (two queries). */
    private List<OptionData> loadOptions(Long masterId) {
        List<MasterProductOption> options = optionRepository.findByMasterProductId(masterId).stream()
                .sorted(Comparator.comparing(MasterProductOption::getId)).toList();
        if (options.isEmpty()) {
            return List.of();
        }
        Map<Long, List<MasterProductOptionItem>> itemsByOption = optionItemRepository
                .findWithProductByOptionIdIn(options.stream().map(MasterProductOption::getId).toList()).stream()
                .collect(Collectors.groupingBy(item -> item.getOption().getId()));
        List<OptionData> result = new ArrayList<>();
        for (MasterProductOption option : options) {
            Map<Long, Integer> vector = new TreeMap<>();
            Map<Long, String> names = new LinkedHashMap<>();
            for (MasterProductOptionItem item : itemsByOption.getOrDefault(option.getId(), List.of())) {
                vector.put(item.getProduct().getId(), item.getQuantity());
                names.put(item.getProduct().getId(), item.getProduct().getProductName());
            }
            result.add(new OptionData(option, vector, names));
        }
        return result;
    }

    /** D24 ②: same quantities and same name → same quantities with the lowest id → null (= 「새 옵션 만들기」). */
    private static Long defaultOptionId(String name, Map<Long, Integer> vector, List<OptionData> keepOptions) {
        List<OptionData> sameVector = keepOptions.stream().filter(o -> o.vector().equals(vector)).toList();
        return sameVector.stream()
                .filter(o -> Objects.equals(trim(o.option().getName()), trim(name)))
                .findFirst()
                .or(() -> sameVector.stream().findFirst())
                .map(o -> o.option().getId())
                .orElse(null);
    }

    /** The quantity vector with {@code fromId} replaced by {@code toId}, ascending by product id. */
    private static Map<Long, Integer> replaced(Map<Long, Integer> vector, Long fromId, Long toId) {
        Map<Long, Integer> result = new TreeMap<>();
        vector.forEach((productId, quantity) -> result.put(productId.equals(fromId) ? toId : productId, quantity));
        return result;
    }

    /** Items ascending by product id; the kept product's name is used for the replaced entry. */
    private static List<MergePreviewResponse.Item> items(Map<Long, Integer> vector, Map<Long, String> names,
                                                         Product to) {
        List<MergePreviewResponse.Item> items = new ArrayList<>();
        vector.forEach((productId, quantity) -> items.add(new MergePreviewResponse.Item(productId,
                to != null && productId.equals(to.getId()) && !names.containsKey(productId)
                        ? to.getProductName() : names.get(productId),
                quantity)));
        return items;
    }

    private MasterOptionResponse createOption(Long masterId, OptionMove move) {
        List<MasterOptionRequest.OptionItem> items = new ArrayList<>();
        move.newVector().forEach((productId, quantity) -> items.add(MasterOptionRequest.OptionItem.builder()
                .productId(productId).quantity(quantity).build()));
        // D24 ④: name + quantities only — delivery, box, category values and stock stay unset (as a new option
        // of a composition save).
        return masterProductService.createOption(masterId,
                MasterOptionRequest.builder().name(move.newOptionName()).items(items).build());
    }

    private MasterProductOption requireOption(Long optionId) {
        return optionRepository.findById(optionId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProductOption", optionId));
    }

    private Product requireProduct(Long id) {
        return productRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    private static String labels(List<MasterProduct> masters) {
        return masters.stream().map(MergeMasterLinkServiceImpl::label).collect(Collectors.joining(", "));
    }

    /** D4: 「{마스터명}(#{번호})」. */
    private static String label(MasterProduct master) {
        return master.getName() + "(#" + master.getId() + ")";
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private record Layout(int sourceMasterCount, List<MasterProduct> replaced, List<MasterPairEntities> pairs) {}

    private record MasterPairEntities(MasterProduct delete, MasterProduct keep) {}

    private record OptionData(MasterProductOption option, Map<Long, Integer> vector, Map<Long, String> names) {}
}
