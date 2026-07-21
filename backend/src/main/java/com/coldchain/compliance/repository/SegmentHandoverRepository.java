package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.SegmentHandover;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SegmentHandoverRepository extends JpaRepository<SegmentHandover, Long> {
    List<SegmentHandover> findByTaskIdOrderByHandoverAtAsc(Long taskId);
    List<SegmentHandover> findByTaskIdAndIsNonTransitTrue(Long taskId);
}
