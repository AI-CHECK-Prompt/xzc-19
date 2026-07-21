package com.coldchain.compliance.service;

import com.coldchain.compliance.dto.AuditSummary;
import com.coldchain.compliance.dto.DecisionRequest;
import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.security.SignatureUtil;
import com.coldchain.compliance.util.Constants;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * 放行决策服务：多源校验汇总。
 * <ul>
 *   <li>审计报告（温度合规）</li>
 *   <li>海关报关单（批号匹配）</li>
 *   <li>检验报告（合格结论）</li>
 * </ul>
 * 三项全部通过 → RELEASE；任一关键失败 → BLOCK；MAJOR 类问题 → 条件放行
 * <p>
 * GxP 强约束：温度合规是 RELEASE 的硬性前置条件，不允许被人工判定覆盖。
 * 飞检场景：海关/检验放行但温度不合规时，必须拒绝人工 RELEASE 并强制 CONDITIONAL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionService {

    private final TransportTaskRepository taskRepo;
    private final ReleaseDecisionRepository decisionRepo;
    private final AuditReportRepository auditRepo;
    private final AuditFindingRepository findingRepo;
    private final CustomsDeclarationRepository customsRepo;
    private final CustomsBatchItemRepository customsItemRepo;
    private final InspectionReportRepository inspectionRepo;
    private final SignatureUtil signatureUtil;
    private final OperationLogService opLogService;

    @Transactional
    public Map<String, Object> decide(DecisionRequest req) {
        TransportTask task = taskRepo.findByTaskNo(req.getTaskNo())
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + req.getTaskNo()));

        // 1) 温度合规（最新审计）：仅 status=PASS 才视为温度合规。
        List<AuditReport> audits = auditRepo.findByTaskIdOrderByStartedAtDesc(task.getId());
        AuditReport latest = audits.isEmpty() ? null : audits.get(0);

        // GxP 强约束：审计历史差异校验（防 BLOCK→PASS 形式合规绕过）
        // 监管场景：审计员对 BLOCK 任务"快速补录几条合规样本 + 立即重审"以拿到 PASS，
        // 监管现场核查发现"同一 task 1 小时内 BLOCK→PASS"轨迹会触发重大合规问询。
        // 约束：若最新审计为 PASS 且存在更早的 BLOCK 审计，必须同时满足
        //   (a) 整改观察窗：latest.started_at 距最近 BLOCK.finished_at ≥ REMEDIATION_WINDOW_SEC
        //   (b) 规则版本非降级：latest.rule_version ≥ 最近 BLOCK.rule_version
        // 任一不满足 → tempOk 判 false（人工 RELEASE 必被既有 !tempOk 硬拦截）。
        AuditReport lastBlockAudit = null;
        for (int i = 1; i < audits.size(); i++) {
            AuditReport a = audits.get(i);
            if (Constants.AUDIT_BLOCK.equals(a.getStatus()) && lastBlockAudit == null) {
                lastBlockAudit = a;
            }
        }
        boolean hasPriorBlockAudit = lastBlockAudit != null;
        long remediationWindowSec = 1800L; // 30 分钟整改观察窗
        String historyRejectReason = null;
        if (hasPriorBlockAudit && latest != null
                && Constants.AUDIT_PASS.equals(latest.getStatus())) {
            if (latest.getRuleVersion() != null && lastBlockAudit.getRuleVersion() != null
                    && latest.getRuleVersion() < lastBlockAudit.getRuleVersion()) {
                historyRejectReason = "rule_version downgrade: latest="
                        + latest.getRuleVersion() + " < priorBlock=" + lastBlockAudit.getRuleVersion();
            } else if (lastBlockAudit.getFinishedAt() != null && latest.getStartedAt() != null) {
                long elapsedSec = java.time.Duration.between(
                        lastBlockAudit.getFinishedAt().toInstant(),
                        latest.getStartedAt().toInstant()).getSeconds();
                if (elapsedSec < remediationWindowSec) {
                    historyRejectReason = "fast re-audit after BLOCK: elapsed=" + elapsedSec
                            + "s < remediationWindow=" + remediationWindowSec + "s";
                }
            }
        }
        boolean historyOk = !hasPriorBlockAudit || historyRejectReason == null;

        // 温度合规判定：仅在历史校验通过时，最新 PASS 才被信任
        boolean tempOk = latest != null
                && Constants.AUDIT_PASS.equals(latest.getStatus())
                && historyOk;
        boolean hasBlock = latest != null && Constants.AUDIT_BLOCK.equals(latest.getStatus());

        // 统计最新审计下各严重度的 finding 数量与温度区间证据，写入 basis 用于飞检追溯
        Map<String, Integer> findingSeverity = new LinkedHashMap<>();
        findingSeverity.put(Constants.SEV_CRITICAL, 0);
        findingSeverity.put(Constants.SEV_MAJOR, 0);
        findingSeverity.put(Constants.SEV_MINOR, 0);
        List<Map<String, Object>> findingBriefs = new ArrayList<>();
        String tempRange = null;
        if (latest != null) {
            List<AuditFinding> fs = findingRepo.findByAuditId(latest.getId());
            java.math.BigDecimal minT = null, maxT = null;
            for (AuditFinding f : fs) {
                String sev = f.getSeverity();
                findingSeverity.merge(sev, 1, Integer::sum);
                Map<String, Object> brief = new LinkedHashMap<>();
                brief.put("id", f.getId());
                brief.put("ruleCode", f.getRuleCode());
                brief.put("severity", sev);
                brief.put("action", f.getAction());
                brief.put("timeRange",
                        (f.getTimeRangeStart() == null ? "" : f.getTimeRangeStart().toString())
                        + " ~ " + (f.getTimeRangeEnd() == null ? "" : f.getTimeRangeEnd().toString()));
                brief.put("description", f.getDescription());
                findingBriefs.add(brief);
                if (f.getTemperatureMin() != null && (minT == null || f.getTemperatureMin().compareTo(minT) < 0)) {
                    minT = f.getTemperatureMin();
                }
                if (f.getTemperatureMax() != null && (maxT == null || f.getTemperatureMax().compareTo(maxT) > 0)) {
                    maxT = f.getTemperatureMax();
                }
            }
            if (minT != null || maxT != null) {
                tempRange = String.format("[%s, %s] ℃",
                        minT == null ? "-" : minT.toPlainString(),
                        maxT == null ? "-" : maxT.toPlainString());
            }
        }

        // 2) 海关报关单
        List<CustomsDeclaration> customs = customsRepo.findByTaskId(task.getId());
        boolean customsOk = !customs.isEmpty() && customs.stream().allMatch(d -> {
            List<CustomsBatchItem> items = customsItemRepo.findByDeclarationId(d.getId());
            return !items.isEmpty() && items.stream().allMatch(i ->
                    "MATCHED".equals(i.getMatchStatus()));
        });

        // 3) 检验报告
        List<InspectionReport> reports = inspectionRepo.findByTaskId(task.getId());
        boolean inspectionOk = !reports.isEmpty() && reports.stream().allMatch(r ->
                "PASS".equals(r.getConclusion()));

        // 决策：GxP 强约束——温度合规是 RELEASE 的硬性前置条件
        String decision;
        if (Constants.DECISION_BLOCK.equals(req.getDecision())) {
            decision = Constants.DECISION_BLOCK;
        } else if (Constants.DECISION_RELEASE.equals(req.getDecision())) {
            // 硬拦截：审计为 BLOCK → 拒绝人工 RELEASE
            if (hasBlock) {
                throw new IllegalStateException("审计结果为 BLOCK，不允许人工 RELEASE");
            }
            // 硬拦截：温度合规未通过（无审计 / 状态为 REVIEW / 存在 MAJOR+ / GxP审计历史差异校验失败）→ 拒绝人工 RELEASE
            if (!tempOk) {
                String reason = "温度合规校验未通过（auditStatus=" + (latest == null ? "NONE" : latest.getStatus()) + "）";
                if (historyRejectReason != null) {
                    reason += "；GxP审计历史差异校验拦截：" + historyRejectReason;
                }
                reason += "。不允许人工 RELEASE，请先完成整改或改用条件放行 CONDITIONAL_RELEASE";
                throw new IllegalStateException(reason);
            }
            decision = customsOk && inspectionOk
                    ? Constants.DECISION_RELEASE
                    : Constants.DECISION_CONDITIONAL;
        } else {
            // CONDITIONAL_RELEASE 或其他：直接落库为条件放行
            decision = Constants.DECISION_CONDITIONAL;
        }

        // 写入决策单
        ReleaseDecision rd = new ReleaseDecision();
        rd.setTaskId(task.getId());
        rd.setDecision(decision);
        rd.setDecidedBy(req.getDecidedBy());
        rd.setAuditReportId(latest == null ? null : latest.getId());
        rd.setCustomsOk(customsOk);
        rd.setInspectionOk(inspectionOk);
        rd.setTemperatureOk(tempOk);
        rd.setComment(req.getComment());

        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("temperatureOk", tempOk);
        basis.put("customsOk", customsOk);
        basis.put("inspectionOk", inspectionOk);
        basis.put("auditStatus", latest == null ? "NONE" : latest.getStatus());
        basis.put("auditId", latest == null ? null : latest.getId());
        basis.put("auditFindingCount", latest == null ? 0 : latest.getFindingCount());
        // GxP 审计历史差异校验结果（飞检追溯用）
        basis.put("auditHistoryCount", audits.size());
        basis.put("hasPriorBlockAudit", hasPriorBlockAudit);
        basis.put("remediationWindowSec", remediationWindowSec);
        basis.put("auditHistoryEnforced", true);
        if (lastBlockAudit != null) {
            basis.put("lastBlockAuditId", lastBlockAudit.getId());
            basis.put("lastBlockFinishedAt", lastBlockAudit.getFinishedAt() == null ? null
                    : lastBlockAudit.getFinishedAt().toString());
            basis.put("priorBlockRuleVersion", lastBlockAudit.getRuleVersion());
        }
        if (latest != null) {
            basis.put("latestRuleVersion", latest.getRuleVersion());
            basis.put("latestStartedAt", latest.getStartedAt() == null ? null
                    : latest.getStartedAt().toString());
        }
        if (lastBlockAudit != null && latest != null
                && lastBlockAudit.getFinishedAt() != null && latest.getStartedAt() != null) {
            basis.put("blockToPassElapsedSec", java.time.Duration.between(
                    lastBlockAudit.getFinishedAt().toInstant(),
                    latest.getStartedAt().toInstant()).getSeconds());
        }
        basis.put("historyRejectReason", historyRejectReason);
        basis.put("findingSeverity", findingSeverity);
        basis.put("findings", findingBriefs);
        basis.put("temperatureRange", tempRange);
        basis.put("customsCount", customs.size());
        basis.put("inspectionCount", reports.size());
        basis.put("acknowledgeFindings", req.getAcknowledgeFindings());
        basis.put("extra", req.getExtra());
        basis.put("gxpEnforced", true);
        basis.put("requestDecision", req.getDecision());
        basis.put("finalDecision", decision);
        String basisJson = JsonUtil.toJson(basis);
        rd.setBasis(basisJson);

        String hash = signatureUtil.sha256Hex(basisJson);
        String sig = signatureUtil.sign(hash);
        rd.setPayloadHash(hash);
        rd.setSignature(sig);
        decisionRepo.save(rd);

        // 联动任务状态：CONDITIONAL_RELEASE 走 TASK_CONDITIONAL 常量，避免硬编码字符串
        if (Constants.DECISION_RELEASE.equals(decision)) {
            task.setStatus(Constants.TASK_RELEASED);
        } else if (Constants.DECISION_BLOCK.equals(decision)) {
            task.setStatus(Constants.TASK_BLOCKED);
        } else {
            task.setStatus(Constants.TASK_CONDITIONAL);
        }
        taskRepo.save(task);

        opLogService.logAsync(req.getDecidedBy(), "QA", "DECIDE", "TASK",
                task.getTaskNo(), basisJson, decision,
                "127.0.0.1", "decision-service");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decisionId", rd.getId());
        result.put("taskNo", task.getTaskNo());
        result.put("decision", decision);
        result.put("taskStatus", task.getStatus());
        result.put("basis", basis);
        result.put("payloadHash", hash);
        result.put("signature", sig);
        result.put("decidedAt", rd.getDecidedAt());
        log.info("【决策-放行】task={} request={} final={} tempOk={} customsOk={} inspectionOk={} auditStatus={} major={} critical={} hasPriorBlock={} historyReject={}",
                task.getTaskNo(), req.getDecision(), decision, tempOk, customsOk, inspectionOk,
                latest == null ? "NONE" : latest.getStatus(),
                findingSeverity.get(Constants.SEV_MAJOR),
                findingSeverity.get(Constants.SEV_CRITICAL),
                hasPriorBlockAudit, historyRejectReason);
        return result;
    }
}

