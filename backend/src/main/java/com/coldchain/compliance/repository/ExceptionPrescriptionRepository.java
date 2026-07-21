package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.ExceptionPrescription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ExceptionPrescriptionRepository extends JpaRepository<ExceptionPrescription, Long> {
    Optional<ExceptionPrescription> findByCode(String code);
    List<ExceptionPrescription> findByDosageFormAndEnabledTrue(String dosageForm);
    List<ExceptionPrescription> findByDosageFormAndExceptionTypeAndEnabledTrue(String dosageForm, String exceptionType);
    List<ExceptionPrescription> findByEnabledTrue();
}
