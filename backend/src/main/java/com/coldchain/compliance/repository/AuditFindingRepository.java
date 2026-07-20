package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.AuditFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditFindingRepository extends JpaRepository<AuditFinding, Long> {
    List<AuditFinding> findByAuditId(Long auditId);
}
