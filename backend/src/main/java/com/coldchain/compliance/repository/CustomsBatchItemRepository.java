package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.CustomsBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CustomsBatchItemRepository extends JpaRepository<CustomsBatchItem, Long> {
    List<CustomsBatchItem> findByDeclarationId(Long declarationId);
    List<CustomsBatchItem> findByDeclaredBatchNo(String declaredBatchNo);
}
