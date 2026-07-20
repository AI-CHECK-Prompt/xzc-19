package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.ReleaseDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReleaseDecisionRepository extends JpaRepository<ReleaseDecision, Long> {
    List<ReleaseDecision> findByTaskIdOrderByDecidedAtDesc(Long taskId);
}
