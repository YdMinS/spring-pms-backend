package com.pms.service;

import com.pms.domain.Seller;
import com.pms.dto.request.CreateSellerRequest;
import com.pms.dto.request.UpdateSellerRequest;
import com.pms.dto.response.SellerResponse;
import com.pms.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerServiceImpl {
    private final SellerRepository sellerRepository;

    @Transactional
    public SellerResponse createSeller(CreateSellerRequest request) {
        if (sellerRepository.findByBusinessRegistration(request.businessRegistration()).isPresent()) {
            throw new IllegalArgumentException("사업자등록번호 '" + request.businessRegistration() + "'는 이미 존재합니다");
        }

        Seller seller = Seller.builder()
            .sellerName(request.sellerName())
            .businessRegistration(request.businessRegistration())
            .build();

        Seller saved = sellerRepository.save(seller);
        return toResponse(saved);
    }

    public SellerResponse getSellerById(Long id) {
        Seller seller = sellerRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("판매자를 찾을 수 없습니다: " + id));
        return toResponse(seller);
    }

    public List<SellerResponse> getAllSellers() {
        return sellerRepository.findAll()
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public SellerResponse updateSeller(Long id, UpdateSellerRequest request) {
        Seller existing = sellerRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("판매자를 찾을 수 없습니다: " + id));

        if (!existing.getBusinessRegistration().equals(request.businessRegistration())) {
            if (sellerRepository.findByBusinessRegistration(request.businessRegistration()).isPresent()) {
                throw new IllegalArgumentException("사업자등록번호 '" + request.businessRegistration() + "'는 이미 존재합니다");
            }
        }

        Seller updated = Seller.builder()
            .id(existing.getId())
            .sellerName(request.sellerName())
            .businessRegistration(request.businessRegistration())
            .build();

        Seller saved = sellerRepository.save(updated);
        return toResponse(saved);
    }

    @Transactional
    public void deleteSeller(Long id) {
        Seller seller = sellerRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("판매자를 찾을 수 없습니다: " + id));
        sellerRepository.delete(seller);
    }

    private SellerResponse toResponse(Seller seller) {
        return new SellerResponse(
            seller.getId(),
            seller.getSellerName(),
            seller.getBusinessRegistration(),
            seller.getCreatedAt(),
            seller.getUpdatedAt()
        );
    }
}
