package com.coldchain.compliance.repository;

import com.coldchain.compliance.entity.CustomsDeclaration;
import com.coldchain.compliance.entity.CustomsBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CustomsDeclarationRepository extends JpaRepository<CustomsDeclaration, Long> {
    Optional<CustomsDeclaration> findByDeclNo(String declNo);
    List<CustomsDeclaration> findByTaskId(Long taskId);
}

