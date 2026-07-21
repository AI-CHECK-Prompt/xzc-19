package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.DoseImpactRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DoseImpactRuleRepository extends JpaRepository<DoseImpactRule, Long> {
    Optional<DoseImpactRule> findByDosageFormAndExceptionType(String dosageForm, String exceptionType);
}
