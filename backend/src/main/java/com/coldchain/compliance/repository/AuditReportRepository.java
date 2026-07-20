package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.AuditReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditReportRepository extends JpaRepository<AuditReport, Long> {
    List<AuditReport> findByTaskIdOrderByStartedAtDesc(Long taskId);
}
