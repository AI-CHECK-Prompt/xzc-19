package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.ComplianceRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ComplianceRuleRepository extends JpaRepository<ComplianceRule, Long> {
    Optional<ComplianceRule> findByCode(String code);
    List<ComplianceRule> findByEnabledTrue();
    List<ComplianceRule> findByDosageFormOrDosageFormIsNull(String dosageForm);
}
