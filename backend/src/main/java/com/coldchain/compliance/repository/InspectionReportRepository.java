package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.InspectionReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface InspectionReportRepository extends JpaRepository<InspectionReport, Long> {
    Optional<InspectionReport> findByReportNo(String reportNo);
    List<InspectionReport> findByTaskId(Long taskId);
}
