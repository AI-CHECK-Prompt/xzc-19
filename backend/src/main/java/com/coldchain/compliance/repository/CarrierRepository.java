package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.Carrier;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CarrierRepository extends JpaRepository<Carrier, Long> {
    Optional<Carrier> findByCarrierCode(String carrierCode);
    List<Carrier> findByCarrierType(String carrierType);
    List<Carrier> findByEnabledTrue();
}
