package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.CarrierWorkorder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CarrierWorkorderRepository extends JpaRepository<CarrierWorkorder, Long> {
    Optional<CarrierWorkorder> findByWorkorderNo(String workorderNo);
    List<CarrierWorkorder> findByTaskId(Long taskId);
    List<CarrierWorkorder> findByCarrierId(Long carrierId);
    List<CarrierWorkorder> findBySegmentId(Long segmentId);
    List<CarrierWorkorder> findByStatus(String status);
}
