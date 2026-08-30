package com.pms.service;

import com.pms.domain.MarginPolicy;
import com.pms.domain.Seller;
import com.pms.dto.request.MarginPolicyRequest;
import com.pms.dto.response.MarginPolicyResponse;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MarginPolicyServiceTest {

    @Mock private MarginPolicyRepository marginPolicyRepository;
    @Mock private SellerRepository sellerRepository;
    @InjectMocks private MarginPolicyServiceImpl service;

    private Seller seller() {
        return Seller.builder().id(3L).sellerName("행복상회").businessRegistration("111-22-33333").build();
    }

    private MarginPolicyRequest request(String platform) {
        return MarginPolicyRequest.builder()
                .sellerId(3L).platform(platform).marginRate(new BigDecimal("0.1500")).build();
    }

    @Test
    void create_duplicateSellerPlatform_throws400_andDoesNotSave() {
        MarginPolicy existing = MarginPolicy.builder().id(1L).seller(seller())
                .platform("COUPANG").marginRate(new BigDecimal("0.1000")).build();
        given(marginPolicyRepository.findBySellerIdAndPlatform(3L, "COUPANG"))
                .willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createMarginPolicy(request("COUPANG")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(marginPolicyRepository, never()).save(any());
    }

    @Test
    void create_valid_savesAndMapsResponse() {
        Seller seller = seller();
        given(marginPolicyRepository.findBySellerIdAndPlatform(3L, "COUPANG")).willReturn(Optional.empty());
        given(sellerRepository.findById(3L)).willReturn(Optional.of(seller));
        given(marginPolicyRepository.save(any())).willReturn(MarginPolicy.builder()
                .id(7L).seller(seller).platform("COUPANG").marginRate(new BigDecimal("0.1500")).build());

        MarginPolicyResponse response = service.createMarginPolicy(request("COUPANG"));

        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getSellerId()).isEqualTo(3L);
        assertThat(response.getSellerName()).isEqualTo("행복상회");
        assertThat(response.getMarginRate()).isEqualByComparingTo("0.1500");
    }

    // 73: displayDiscountRate is persisted on create and returned in the response.
    @Test
    void create_persistsAndReturnsDisplayDiscountRate() {
        Seller seller = seller();
        MarginPolicyRequest req = MarginPolicyRequest.builder()
                .sellerId(3L).platform("COUPANG").marginRate(new BigDecimal("0.1500"))
                .displayDiscountRate(new BigDecimal("0.2000")).build();
        given(marginPolicyRepository.findBySellerIdAndPlatform(3L, "COUPANG")).willReturn(Optional.empty());
        given(sellerRepository.findById(3L)).willReturn(Optional.of(seller));
        given(marginPolicyRepository.save(any())).willAnswer(inv -> {
            MarginPolicy p = inv.getArgument(0);
            return p.toBuilder().id(7L).build();
        });

        MarginPolicyResponse response = service.createMarginPolicy(req);

        org.mockito.ArgumentCaptor<MarginPolicy> captor = org.mockito.ArgumentCaptor.forClass(MarginPolicy.class);
        verify(marginPolicyRepository).save(captor.capture());
        assertThat(captor.getValue().getDisplayDiscountRate()).isEqualByComparingTo("0.2000");
        assertThat(response.getDisplayDiscountRate()).isEqualByComparingTo("0.2000");
    }

    // 73: displayDiscountRate=null on update keeps the existing value (optional-field convention).
    @Test
    void update_nullDisplayDiscountRate_keepsExisting() {
        Seller seller = seller();
        MarginPolicy self = MarginPolicy.builder().id(5L).seller(seller)
                .platform("COUPANG").marginRate(new BigDecimal("0.1000"))
                .displayDiscountRate(new BigDecimal("0.3000")).build();
        given(marginPolicyRepository.findById(5L)).willReturn(Optional.of(self));
        given(marginPolicyRepository.findBySellerIdAndPlatform(3L, "COUPANG")).willReturn(Optional.empty());
        given(sellerRepository.findById(3L)).willReturn(Optional.of(seller));
        given(marginPolicyRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // request() carries no displayDiscountRate (null) → existing 0.3000 preserved.
        MarginPolicyResponse response = service.updateMarginPolicy(5L, request("COUPANG"));

        assertThat(response.getDisplayDiscountRate()).isEqualByComparingTo("0.3000");
    }

    @Test
    void update_duplicateOnAnotherRecord_throws400_andDoesNotSave() {
        MarginPolicy self = MarginPolicy.builder().id(5L).seller(seller())
                .platform("COUPANG").marginRate(new BigDecimal("0.1000")).build();
        MarginPolicy other = MarginPolicy.builder().id(9L).seller(seller())
                .platform("NAVER").marginRate(new BigDecimal("0.2000")).build();
        given(marginPolicyRepository.findById(5L)).willReturn(Optional.of(self));
        given(marginPolicyRepository.findBySellerIdAndPlatform(3L, "NAVER")).willReturn(Optional.of(other));

        assertThatThrownBy(() -> service.updateMarginPolicy(5L, request("NAVER")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(marginPolicyRepository, never()).save(any());
    }
}
