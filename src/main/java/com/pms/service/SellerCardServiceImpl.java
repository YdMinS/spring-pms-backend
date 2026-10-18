package com.pms.service;

import com.pms.domain.Seller;
import com.pms.domain.SellerCard;
import com.pms.dto.request.SellerCardCreateRequest;
import com.pms.dto.request.SellerCardUpdateRequest;
import com.pms.dto.response.SellerCardView;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.SellerCardRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.coupang.SyncWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link SellerCardService} implementation.
 *
 * Class default readOnly, write methods override with @Transactional. Entities have no setters — updates rebuild
 * with toBuilder.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerCardServiceImpl implements SellerCardService {

    /** 13 or more digits in a row = a full card number (D92 · D99 ②). */
    private static final Pattern FULL_NUMBER = Pattern.compile("\\d{13,}");

    private final SellerCardRepository sellerCardRepository;
    private final SellerRepository sellerRepository;
    private final Clock clock;

    @Override
    public List<SellerCardView> getVisible() {
        return sellerCardRepository.findVisible().stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public SellerCardView create(SellerCardCreateRequest request) {
        String masked = request.maskedNumber().trim();
        rejectFullNumber(masked);
        Seller seller = sellerRepository.findById(request.sellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", request.sellerId()));
        SellerCard saved = sellerCardRepository.save(SellerCard.builder()
                .seller(seller)
                .alias(request.alias().trim())
                .maskedNumber(masked)
                .build());
        return toView(saved);
    }

    @Override
    @Transactional
    public SellerCardView update(Long cardId, SellerCardUpdateRequest request) {
        String masked = request.maskedNumber().trim();
        rejectFullNumber(masked);
        SellerCard card = requireVisible(cardId);
        // D91: alias and masked number only — the owner seller stays.
        SellerCard saved = sellerCardRepository.save(card.toBuilder()
                .alias(request.alias().trim())
                .maskedNumber(masked)
                .build());
        return toView(saved);
    }

    @Override
    @Transactional
    public void hide(Long cardId) {
        SellerCard card = requireVisible(cardId);
        sellerCardRepository.save(card.toBuilder()
                .hiddenAt(LocalDateTime.ofInstant(clock.instant(), SyncWindow.KST))
                .build());
    }

    @Override
    public SellerCard requireVisible(Long cardId) {
        return sellerCardRepository.findById(cardId)
                .filter(card -> card.getHiddenAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("SellerCard", cardId));
    }

    /** D92 · D99 ②: count digits with hyphens and spaces removed — 「1234-5678-9012-3456」 is blocked too. */
    private void rejectFullNumber(String maskedNumber) {
        String digitsOnlyRun = maskedNumber.replaceAll("[\\s-]", "");
        if (FULL_NUMBER.matcher(digitsOnlyRun).find()) {
            throw new IllegalArgumentException("가려진 번호만 넣으세요");
        }
    }

    private SellerCardView toView(SellerCard card) {
        return new SellerCardView(card.getId(), card.getSeller().getId(), card.getSeller().getSellerName(),
                card.getAlias(), card.getMaskedNumber());
    }
}
