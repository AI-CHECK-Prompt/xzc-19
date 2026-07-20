package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.TransportTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface TransportTaskRepository extends JpaRepository<TransportTask, Long> {
    Optional<TransportTask> findByTaskNo(String taskNo);
    List<TransportTask> findByStatus(String status);

    @Query("SELECT t FROM TransportTask t WHERE (:taskNo IS NULL OR t.taskNo = :taskNo) " +
           "AND (:status IS NULL OR t.status = :status) " +
           "AND (:from IS NULL OR t.departureAt >= :from) " +
           "AND (:to IS NULL OR t.departureAt <= :to) " +
           "ORDER BY t.departureAt DESC")
    List<TransportTask> search(@Param("taskNo") String taskNo,
                                @Param("status") String status,
                                @Param("from") OffsetDateTime from,
                                @Param("to") OffsetDateTime to);
}
