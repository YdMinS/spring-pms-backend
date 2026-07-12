package com.pms.repository;

import com.pms.domain.Carrier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CarrierRepository extends JpaRepository<Carrier, Long> {

    /** 활성 택배사. 정렬 미지정이면 "첫 행"이 비결정적이므로 id 오름차순으로 결정적 조회. */
    List<Carrier> findByIsActiveTrueOrderByIdAsc();
}
