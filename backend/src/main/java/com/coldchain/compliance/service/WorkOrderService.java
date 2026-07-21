package com.coldchain.compliance.service;

import com.coldchain.compliance.entity.CarrierWorkorder;
import com.coldchain.compliance.repository.CarrierWorkorderRepository;
import com.coldchain.compliance.util.ClockUtil;
import com.coldchain.compliance.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 承运商整改工单服务。
 * <p>
 * 多式联运协同引擎在某段发生异常时，自动给责任段承运商开具工单，
 * 并写明：异常类型、严重度、影响剂量、关联预案、处置时效、是否触发监管报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderService {

    private final CarrierWorkorderRepository repo;

    @Transactional
    public CarrierWorkorder create(Long taskId, String taskNo, Long segmentId, Long carrierId,
                                   String exceptionType, String severity, String title,
                                   String description, Long prescriptionId,
                                   Integer affectedQty, String responsibleParty,
                                   Integer responseHours, boolean regulatoryReport) {
        CarrierWorkorder wo = new CarrierWorkorder();
        String suffix = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String base = (taskNo == null || taskNo.isEmpty()) ? "T" + taskId : taskNo;
        wo.setWorkorderNo("WO-" + base + "-" + System.currentTimeMillis() + "-" + suffix);
        wo.setTaskId(taskId);
        wo.setSegmentId(segmentId);
        wo.setCarrierId(carrierId);
        wo.setExceptionType(exceptionType);
        wo.setSeverity(severity);
        wo.setTitle(title);
        wo.setDescription(description);
        wo.setPrescriptionId(prescriptionId);
        wo.setAffectedQty(affectedQty);
        wo.setResponsibleParty(responsibleParty == null ? Constants.PARTY_CARRIER : responsibleParty);
        if (responseHours != null && responseHours > 0) {
            wo.setResponseDeadline(ClockUtil.nowUtc().plusHours(responseHours));
        }
        wo.setStatus(regulatoryReport ? Constants.WO_OVERDUE : Constants.WO_OPEN);
        CarrierWorkorder saved = repo.save(wo);
        log.info("【工单-开具】{} segment={} carrier={} exception={} severity={} qty={}",
                saved.getWorkorderNo(), segmentId, carrierId, exceptionType, severity, affectedQty);
        return saved;
    }

    public List<CarrierWorkorder> listByTask(Long taskId) { return repo.findByTaskId(taskId); }
    public List<CarrierWorkorder> listByCarrier(Long carrierId) { return repo.findByCarrierId(carrierId); }
    public List<CarrierWorkorder> listBySegment(Long segmentId) { return repo.findBySegmentId(segmentId); }
    public List<CarrierWorkorder> listAll() { return repo.findAll(); }

    @Transactional
    public CarrierWorkorder resolve(Long id, String note) {
        CarrierWorkorder wo = repo.findById(id).orElse(null);
        if (wo == null) return null;
        wo.setStatus(Constants.WO_RESOLVED);
        wo.setResolvedAt(ClockUtil.nowUtc());
        wo.setResolvedNote(note);
        log.info("【工单-关闭】{} status={}", wo.getWorkorderNo(), wo.getStatus());
        return repo.save(wo);
    }
}
