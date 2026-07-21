package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.SegmentFindingRel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SegmentFindingRelRepository extends JpaRepository<SegmentFindingRel, Long> {
    List<SegmentFindingRel> findBySegmentId(Long segmentId);
    List<SegmentFindingRel> findByFindingId(Long findingId);
}
