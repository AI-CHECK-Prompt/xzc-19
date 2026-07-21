package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.TransportSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TransportSegmentRepository extends JpaRepository<TransportSegment, Long> {
    List<TransportSegment> findByTaskIdOrderBySeqAsc(Long taskId);
    List<TransportSegment> findByTaskIdAndIsInTransitTrue(Long taskId);
    List<TransportSegment> findByCarrierId(Long carrierId);
    Optional<TransportSegment> findByTaskIdAndSegmentNo(Long taskId, String segmentNo);
}
