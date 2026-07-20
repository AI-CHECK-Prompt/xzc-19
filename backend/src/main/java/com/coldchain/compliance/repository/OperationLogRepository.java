package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    @Query("SELECT o FROM OperationLog o ORDER BY o.id DESC")
    org.springframework.data.domain.Page<OperationLog> findAllDesc(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT o FROM OperationLog o WHERE o.id = :id")
    Optional<OperationLog> findById(@Param("id") Long id);
}
