package com.pms.service.listing;

import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingCategorySourceResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.MasterChannelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Default {@link ListingCategorySourceService}. See the interface for the rules. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingCategorySourceServiceImpl implements ListingCategorySourceService {

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterChannelConfigService masterChannelConfigService;

    @Override
    @Transactional
    public ListingCategorySourceResponse updateCategorySource(Long listingId, boolean useMasterCategory) {
        ProductListing cell = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        if (!useMasterCategory) {
            // D13: 채널 카테고리의 출처는 가져오기 하나뿐이라 복원할 원본이 없다 — 고르는 UI 를 만들지 않는다.
            throw new IllegalArgumentException("채널 카테고리는 가져오기로만 설정됩니다");
        }

        String previousCategoryCode = cell.getPlatformCategoryCode();
        ProductListing updated = productListingRepository.save(cell.toBuilder()
                .platformCategoryCode(null)
                .categoryNoticeGroup(null)
                .build());

        // 🔴 셀 옵션 메타는 **옛 카테고리 스키마의 값**이다. 남기면 3단 병합에서 셀이 마스터를 이겨,
        //    새 카테고리에 없는 항목이 전송되거나 필수값이 빈 채로 나간다.
        List<ProductListingOption> cleared = new ArrayList<>();
        for (ProductListingOption option : productListingOptionRepository.findByProductListingId(listingId)) {
            if (option.getCategoryAttributes() == null && option.getCategoryNotices() == null) {
                continue;                       // 이미 비어 있다 — 불필요한 UPDATE 를 만들지 않는다
            }
            cleared.add(option.toBuilder()
                    .categoryAttributes(null)
                    .categoryNotices(null)
                    .build());
        }
        if (!cleared.isEmpty()) {
            productListingOptionRepository.saveAll(cleared);
        }

        return ListingCategorySourceResponse.builder()
                .productListingId(listingId)
                .useMasterCategory(true)
                .effectiveCategoryCode(effectiveCategoryCode(updated))
                .previousCategoryCode(previousCategoryCode)
                .build();
    }

    /**
     * 전환 후 이 셀이 실제로 쓰게 될 코드. ⚠️ 마스터에 카테고리·매핑이 없으면 resolver 는 400 을 던지는데,
     * 그 400 이 나가면 <b>비우기 자체가 롤백</b>된다 — 안내 문구용 값 하나 때문에 조작을 막을 이유가 없으므로
     * null 로 둔다(그 셀은 어차피 판매가 계산에서 같은 400 으로 드러난다).
     */
    private String effectiveCategoryCode(ProductListing cell) {
        try {
            return masterChannelConfigService.resolveChannelCategory(cell).category().getCode();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
