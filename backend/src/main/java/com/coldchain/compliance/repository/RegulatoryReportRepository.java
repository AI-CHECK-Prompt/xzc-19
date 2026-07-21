package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.RegulatoryReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RegulatoryReportRepository extends JpaRepository<RegulatoryReport, Long> {
    Optional<RegulatoryReport> findByReportNo(String reportNo);
    List<RegulatoryReport> findByTaskId(Long taskId);
}
