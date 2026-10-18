package com.pms.service;

import com.pms.domain.Seller;
import com.pms.domain.SellerCard;
import com.pms.dto.request.SellerCardCreateRequest;
import com.pms.dto.request.SellerCardUpdateRequest;
import com.pms.dto.response.SellerCardView;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.SellerCardRepository;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** SellerCardServiceImpl — full-number check (D92 · D99 ②), owner kept on edit and hide (D91). */
@ExtendWith(MockitoExtension.class)
class SellerCardServiceImplTest {

    @Mock private SellerCardRepository sellerCardRepository;
    @Mock private SellerRepository sellerRepository;
    @Mock private Clock clock;

    @InjectMocks private SellerCardServiceImpl service;

    private static final Seller SELLER = Seller.builder().id(3L).sellerName("A상사").build();

    private SellerCard card(LocalDateTime hiddenAt) {
        return SellerCard.builder().id(7L).seller(SELLER).alias("법인카드").maskedNumber("1234-56**-****-3456")
                .hiddenAt(hiddenAt).build();
    }

    /** D99 ②: hyphens and spaces are removed before counting — a hyphenated full number is still blocked. */
    @Test
    void testCreateRejectsFullNumberWithHyphens() {
        assertThatThrownBy(() -> service.create(
                new SellerCardCreateRequest(3L, "법인카드", "1234-5678-9012-3456")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("가려진 번호만 넣으세요");
        verify(sellerCardRepository, never()).save(any());
    }

    /** D52: a masked number is stored as typed (trimmed). */
    @Test
    void testCreateKeepsMaskedNumberAsTyped() {
        given(sellerRepository.findById(3L)).willReturn(Optional.of(SELLER));
        given(sellerCardRepository.save(any(SellerCard.class)))
                .willAnswer(inv -> inv.<SellerCard>getArgument(0).toBuilder().id(7L).build());

        SellerCardView view = service.create(new SellerCardCreateRequest(3L, " 법인카드 ", " 1234-56**-****-3456 "));

        assertThat(view.maskedNumber()).isEqualTo("1234-56**-****-3456");
        assertThat(view.alias()).isEqualTo("법인카드");
        assertThat(view.sellerId()).isEqualTo(3L);
    }

    /** D91: edit changes alias and number only; the same full-number check applies. */
    @Test
    void testUpdateRejectsFullNumber() {
        assertThatThrownBy(() -> service.update(7L, new SellerCardUpdateRequest("법인카드", "1234 5678 9012 3456")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("가려진 번호만 넣으세요");
    }

    /** D91: delete = hide — the row stays with hiddenAt set, the owner unchanged. */
    @Test
    void testHideSetsHiddenAt() {
        given(sellerCardRepository.findById(7L)).willReturn(Optional.of(card(null)));
        given(clock.instant()).willReturn(Instant.parse("2026-10-18T03:00:00Z"));

        service.hide(7L);

        ArgumentCaptor<SellerCard> captor = ArgumentCaptor.forClass(SellerCard.class);
        verify(sellerCardRepository).save(captor.capture());
        assertThat(captor.getValue().getHiddenAt()).isEqualTo(LocalDateTime.of(2026, 10, 18, 12, 0));
        assertThat(captor.getValue().getSeller()).isSameAs(SELLER);
    }

    /** D91: a hidden card cannot be picked or edited again — 404. */
    @Test
    void testRequireVisibleRejectsHiddenCard() {
        given(sellerCardRepository.findById(7L)).willReturn(Optional.of(card(LocalDateTime.of(2026, 10, 1, 9, 0))));

        assertThatThrownBy(() -> service.requireVisible(7L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
