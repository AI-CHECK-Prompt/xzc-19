package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.ExportRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportRecordRepository extends JpaRepository<ExportRecord, Long> {
}
