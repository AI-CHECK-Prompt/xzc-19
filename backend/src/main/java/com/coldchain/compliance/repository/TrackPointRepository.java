package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.TrackPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface TrackPointRepository extends JpaRepository<TrackPoint, Long> {
    @Query("SELECT t FROM TrackPoint t WHERE t.taskId = :taskId ORDER BY t.sampleAt ASC")
    List<TrackPoint> findByTaskIdOrderBySampleAt(@Param("taskId") Long taskId);
}
