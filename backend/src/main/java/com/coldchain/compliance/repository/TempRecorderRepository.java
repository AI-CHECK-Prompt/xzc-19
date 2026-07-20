package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.TempRecorder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TempRecorderRepository extends JpaRepository<TempRecorder, Long> {
    Optional<TempRecorder> findByDeviceNo(String deviceNo);
}
