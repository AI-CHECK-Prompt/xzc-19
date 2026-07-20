package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.DrugBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DrugBatchRepository extends JpaRepository<DrugBatch, Long> {
    Optional<DrugBatch> findByBatchNo(String batchNo);
    List<DrugBatch> findByDosageForm(String dosageForm);
}
