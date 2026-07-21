package com.coldchain.compliance.service;

import com.coldchain.compliance.engine.DoseImpactCalculator;
import com.coldchain.compliance.engine.ResponsibilityResolver;
import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.RegulatoryReportRepository;
import com.coldchain.compliance.security.SignatureUtil;
import com.coldchain.compliance.util.ClockUtil;
import com.coldchain.compliance.util.Constants;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 监管报告自动生成服务。预案中 regulatoryReport=true 时触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegulatoryReportService {

    private final RegulatoryReportRepository repo;
    private final SignatureUtil signatureUtil;

    @Transactional
    public RegulatoryReport generate(TransportTask task,
                                     ResponsibilityResolver.ResponsibilityResult resp,
                                     AuditFinding finding,
                                     DoseImpactCalculator.DoseResult dose,
                                     ExceptionPrescription prescription) {
        RegulatoryReport rr = new RegulatoryReport();
        rr.setReportNo("REG-" + task.getTaskNo() + "-" + System.currentTimeMillis());
        rr.setTaskId(task.getId());
        rr.setReportType(Constants.RR_DRAFT.equals("DRAFT") ? "INCIDENT" : "INCIDENT");
        rr.setTitle(String.format("[%s] 多式联运异常事件监管报告 - 任务 %s",
                finding.getSeverity(), task.getTaskNo()));
        rr.setTriggeredBy(prescription == null ? finding.getRuleCode() : prescription.getExceptionType());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskNo", task.getTaskNo());
        body.put("origin", task.getOrigin());
        body.put("destination", task.getDestination());
        body.put("exceptionType", prescription == null ? null : prescription.getExceptionType());
        body.put("prescriptionCode", prescription == null ? null : prescription.getCode());
        body.put("findingDescription", finding.getDescription());
        body.put("affectedDoseRatio", dose.affectedDoseRatio);
        body.put("estimatedAffectedQty", dose.estimatedAffectedQty);
        body.put("responsibleSegmentNo", resp.segmentNo);
        body.put("responsibleCarrierId", resp.carrierId);
        body.put("responsibleParty", resp.responsibleParty);
        body.put("timeRangeStart", finding.getTimeRangeStart() == null ? null : finding.getTimeRangeStart().toString());
        body.put("timeRangeEnd", finding.getTimeRangeEnd() == null ? null : finding.getTimeRangeEnd().toString());
        body.put("severity", finding.getSeverity());
        body.put("actions", prescription == null ? null : JsonUtil.toList(prescription.getActionsJson()));
        body.put("reportedAt", ClockUtil.nowUtc().toString());

        String bodyJson = JsonUtil.toJson(body);
        rr.setBodyJson(bodyJson);
        rr.setBodyText(buildPlainText(task, resp, finding, dose, prescription, body));
        rr.setStatus(Constants.RR_DRAFT);

        String hash = signatureUtil.sha256Hex(bodyJson);
        rr.setPayloadHash(hash);
        rr.setSignature(signatureUtil.sign(hash));

        RegulatoryReport saved = repo.save(rr);
        log.info("【监管报告】生成 {} task={} 异常={} 影响={}",
                saved.getReportNo(), task.getTaskNo(), rr.getTriggeredBy(), dose.estimatedAffectedQty);
        return saved;
    }

    public Map<String, Object> toVo(RegulatoryReport rr) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", rr.getId());
        vo.put("reportNo", rr.getReportNo());
        vo.put("taskId", rr.getTaskId());
        vo.put("reportType", rr.getReportType());
        vo.put("title", rr.getTitle());
        vo.put("triggeredBy", rr.getTriggeredBy());
        vo.put("status", rr.getStatus());
        vo.put("submittedAt", rr.getSubmittedAt());
        vo.put("createdAt", rr.getCreatedAt());
        vo.put("body", rr.getBodyJson() == null ? null : JsonUtil.toMap(rr.getBodyJson()));
        vo.put("payloadHash", rr.getPayloadHash());
        vo.put("signature", rr.getSignature());
        return vo;
    }

    public List<Map<String, Object>> listByTask(Long taskId) {
        List<RegulatoryReport> list = repo.findByTaskId(taskId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RegulatoryReport r : list) out.add(toVo(r));
        return out;
    }

    public List<Map<String, Object>> listAll() {
        List<RegulatoryReport> list = repo.findAll();
        List<Map<String, Object>> out = new ArrayList<>();
        for (RegulatoryReport r : list) out.add(toVo(r));
        return out;
    }

    @Transactional
    public RegulatoryReport submit(Long id) {
        RegulatoryReport rr = repo.findById(id).orElse(null);
        if (rr == null) return null;
        rr.setStatus(Constants.RR_SUBMITTED);
        rr.setSubmittedAt(ClockUtil.nowUtc());
        log.info("【监管报告】提交 {} status={}", rr.getReportNo(), rr.getStatus());
        return repo.save(rr);
    }

    private String buildPlainText(TransportTask task,
                                   ResponsibilityResolver.ResponsibilityResult resp,
                                   AuditFinding f, DoseImpactCalculator.DoseResult dose,
                                   ExceptionPrescription p, Map<String, Object> body) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 多式联运异常事件监管报告 ===\n");
        sb.append("任务号: ").append(task.getTaskNo()).append("\n");
        sb.append("路线: ").append(task.getOrigin()).append(" → ").append(task.getDestination()).append("\n");
        sb.append("异常类型: ").append(p == null ? f.getRuleCode() : p.getExceptionType()).append("\n");
        sb.append("严重度: ").append(f.getSeverity()).append("\n");
        sb.append("发生时间: ").append(f.getTimeRangeStart()).append(" ~ ").append(f.getTimeRangeEnd()).append("\n");
        sb.append("责任段: ").append(resp.segmentNo).append("  责任方: ").append(resp.responsibleParty).append("\n");
        sb.append("影响药品剂量比例: ").append(String.format("%.2f%%", dose.affectedDoseRatio * 100)).append("\n");
        sb.append("受影响剂数(估算): ").append(dose.estimatedAffectedQty).append("\n");
        if (p != null) {
            sb.append("建议动作:\n");
            for (Object obj : JsonUtil.toList(p.getActionsJson())) {
                sb.append("  - ").append(obj).append("\n");
            }
            sb.append("处置时效: ").append(p.getResponseHours()).append(" 小时\n");
        }
        sb.append("报告生成时间: ").append(ClockUtil.nowUtc()).append("\n");
        sb.append("========================\n");
        return sb.toString();
    }
}
