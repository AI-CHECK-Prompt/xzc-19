package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.MultiChainCompliance;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MultiChainComplianceRepository extends JpaRepository<MultiChainCompliance, Long> {
    Optional<MultiChainCompliance> findByTaskId(Long taskId);
}
