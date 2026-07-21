package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.ResponsibilityRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ResponsibilityRuleRepository extends JpaRepository<ResponsibilityRule, Long> {
    List<ResponsibilityRule> findByExceptionTypeAndEnabledTrueOrderByPriorityAsc(String exceptionType);
}
