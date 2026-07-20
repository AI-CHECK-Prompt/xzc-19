package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.TempSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface TempSampleRepository extends JpaRepository<TempSample, Long> {
    Optional<TempSample> findTopByDeviceNoOrderBySeqNoDesc(String deviceNo);

    @Query("SELECT s FROM TempSample s WHERE s.taskId = :taskId ORDER BY s.sampleAt ASC")
    List<TempSample> findByTaskIdOrderBySampleAt(@Param("taskId") Long taskId);

    @Query("SELECT s FROM TempSample s WHERE s.deviceNo = :deviceNo AND s.sampleAt >= :from AND s.sampleAt <= :to ORDER BY s.sampleAt ASC")
    List<TempSample> findByDeviceAndRange(@Param("deviceNo") String deviceNo,
                                          @Param("from") OffsetDateTime from,
                                          @Param("to") OffsetDateTime to);
}
