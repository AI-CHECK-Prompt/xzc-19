package com.coldchain.compliance.service;

import com.coldchain.compliance.dto.DecisionRequest;
import com.coldchain.compliance.entity.AuditFinding;
import com.coldchain.compliance.entity.AuditReport;
import com.coldchain.compliance.entity.CustomsBatchItem;
import com.coldchain.compliance.entity.CustomsDeclaration;
import com.coldchain.compliance.entity.InspectionReport;
import com.coldchain.compliance.entity.ReleaseDecision;
import com.coldchain.compliance.entity.TransportTask;
import com.coldchain.compliance.repository.AuditFindingRepository;
import com.coldchain.compliance.repository.AuditReportRepository;
import com.coldchain.compliance.repository.CustomsBatchItemRepository;
import com.coldchain.compliance.repository.CustomsDeclarationRepository;
import com.coldchain.compliance.repository.InspectionReportRepository;
import com.coldchain.compliance.repository.ReleaseDecisionRepository;
import com.coldchain.compliance.repository.TransportTaskRepository;
import com.coldchain.compliance.security.SignatureUtil;
import com.coldchain.compliance.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归测试：DecisionService GxP 硬拦截。
 * <p>
 * 飞检场景：海关报关单全部 MATCHED、检验报告 PASS，但温度审计存在 2 条 MAJOR finding
 * （auditStatus=REVIEW，温度不合规）。原实现只校验 hasBlock，user 传 RELEASE 时
 * 仍落库 decision=RELEASE → 违反 GxP。修复后必须硬性拒绝并抛 IllegalStateException。
 * <p>
 * 额外覆盖：
 * <ul>
 *   <li>auditStatus=PASS + 海关/检验均通过 → RELEASE 落库，task 状态联动 RELEASED</li>
 *   <li>auditStatus=BLOCK → 拒绝人工 RELEASE（保留原行为）</li>
 *   <li>auditStatus=PASS 但海关不通过 → 自动降级 CONDITIONAL_RELEASE，task 状态 CONDITIONAL</li>
 *   <li>audit_basis 必须记录 findingSeverity 与 temperatureRange 用于飞检追溯</li>
 * </ul>
 */
class DecisionServiceTest {

    @TempDir
    Path tmpKeyDir;

    private TransportTaskRepository taskRepo;
    private ReleaseDecisionRepository decisionRepo;
    private AuditReportRepository auditRepo;
    private AuditFindingRepository findingRepo;
    private CustomsDeclarationRepository customsRepo;
    private CustomsBatchItemRepository customsItemRepo;
    private InspectionReportRepository inspectionRepo;
    private SignatureUtil signatureUtil;
    private OperationLogService opLogService;
    private DecisionService decisionService;

    @BeforeEach
    void setUp() throws Exception {
        taskRepo = mock(TransportTaskRepository.class);
        decisionRepo = mock(ReleaseDecisionRepository.class);
        auditRepo = mock(AuditReportRepository.class);
        findingRepo = mock(AuditFindingRepository.class);
        customsRepo = mock(CustomsDeclarationRepository.class);
        customsItemRepo = mock(CustomsBatchItemRepository.class);
        inspectionRepo = mock(InspectionReportRepository.class);
        opLogService = mock(OperationLogService.class);

        signatureUtil = new SignatureUtil();
        Field keyDirField = SignatureUtil.class.getDeclaredField("keyDir");
        keyDirField.setAccessible(true);
        keyDirField.set(signatureUtil, tmpKeyDir.toString());
        ReflectionTestUtils.invokeMethod(signatureUtil, "init");

        // 任务基本数据
        TransportTask task = new TransportTask();
        task.setId(100L);
        task.setTaskNo("T-FDA-001");
        task.setStatus("ARRIVED");
        when(taskRepo.findByTaskNo("T-FDA-001")).thenReturn(Optional.of(task));

        // decisionRepo.save 模拟持久化填充 id
        AtomicLong idGen = new AtomicLong(0);
        when(decisionRepo.save(any(ReleaseDecision.class))).thenAnswer(inv -> {
            ReleaseDecision d = inv.getArgument(0);
            if (d.getId() == null) d.setId(idGen.incrementAndGet());
            return d;
        });
        when(taskRepo.save(any(TransportTask.class))).thenAnswer(inv -> inv.getArgument(0));

        decisionService = new DecisionService(taskRepo, decisionRepo, auditRepo, findingRepo,
                customsRepo, customsItemRepo, inspectionRepo, signatureUtil, opLogService);
    }

    /**
     * 核心 GxP 场景：温控 2 条 MAJOR finding + 海关全 MATCHED + 检验 PASS
     * → 人工传 RELEASE 必须被拒绝，温度是放行硬性前置。
     */
    @Test
    void decide_releaseWhenTempNotOk_isRejectedByGxp() {
        // 审计：REVIEW（有 MAJOR finding，温度不合规）
        AuditReport report = new AuditReport();
        report.setId(1L);
        report.setTaskId(100L);
        report.setStatus(Constants.AUDIT_REVIEW);
        report.setStartedAt(OffsetDateTime.parse("2026-07-20T00:00:00Z"));
        report.setFinishedAt(OffsetDateTime.parse("2026-07-20T01:00:00Z"));
        report.setFindingCount(2);
        when(auditRepo.findByTaskIdOrderByStartedAtDesc(100L))
                .thenReturn(new ArrayList<>(Arrays.asList(report)));

        // 2 条 MAJOR finding（飞检案例描述）
        List<AuditFinding> findings = Arrays.asList(
                finding(10L, "R-RANGE-001", Constants.SEV_MAJOR, "9.5", "10.2"),
                finding(11L, "R-RANGE-001", Constants.SEV_MAJOR, "10.1", "10.8")
        );
        when(findingRepo.findByAuditId(1L)).thenReturn(findings);

        // 海关全部 MATCHED
        whenCustomsMatched();
        // 检验全部 PASS
        whenInspectionAllPass();

        DecisionRequest req = new DecisionRequest();
        req.setTaskNo("T-FDA-001");
        req.setDecision(Constants.DECISION_RELEASE);  // 海关对接员人工选 RELEASE
        req.setDecidedBy("CUSTOMS-OP-01");
        req.setComment("已核对海关/检验，温度小问题先放行");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> decisionService.decide(req),
                "温度不合规时必须拒绝人工 RELEASE，不能被人工判定覆盖");
        assertTrue(ex.getMessage().contains("温度合规校验未通过"),
                "异常消息必须明确温度不合规原因");
        assertTrue(ex.getMessage().contains("auditStatus=REVIEW"),
                "异常消息必须包含 audit 状态以便飞检追溯");
    }

    /**
     * 场景：auditStatus=PASS + 海关全匹配 + 检验全 PASS → 正常 RELEASE 落库。
     */
    @Test
    void decide_allPass_releaseLands() {
        whenAuditPass();
        when(findingRepo.findByAuditId(1L)).thenReturn(Collections.emptyList());
        whenCustomsMatched();
        whenInspectionAllPass();

        DecisionRequest req = new DecisionRequest();
        req.setTaskNo("T-FDA-001");
        req.setDecision(Constants.DECISION_RELEASE);
        req.setDecidedBy("QA-01");
        req.setComment("全部通过");

        Map<String, Object> result = decisionService.decide(req);

        assertEquals(Constants.DECISION_RELEASE, result.get("decision"));
        assertEquals(Constants.TASK_RELEASED, result.get("taskStatus"));
        @SuppressWarnings("unchecked")
        Map<String, Object> basis = (Map<String, Object>) result.get("basis");
        assertEquals(true, basis.get("temperatureOk"));
        assertEquals(true, basis.get("customsOk"));
        assertEquals(true, basis.get("inspectionOk"));
        assertEquals(true, basis.get("gxpEnforced"));
    }

    /**
     * 场景：auditStatus=BLOCK → 仍然拒绝人工 RELEASE（保留原 GxP 行为）。
     */
    @Test
    void decide_auditBlock_stillRejectsRelease() {
        AuditReport report = new AuditReport();
        report.setId(1L);
        report.setTaskId(100L);
        report.setStatus(Constants.AUDIT_BLOCK);
        report.setFindingCount(1);
        when(auditRepo.findByTaskIdOrderByStartedAtDesc(100L))
                .thenReturn(new ArrayList<>(Arrays.asList(report)));
        when(findingRepo.findByAuditId(1L)).thenReturn(Collections.emptyList());
        whenCustomsMatched();
        whenInspectionAllPass();

        DecisionRequest req = new DecisionRequest();
        req.setTaskNo("T-FDA-001");
        req.setDecision(Constants.DECISION_RELEASE);
        req.setDecidedBy("CUSTOMS-OP-01");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> decisionService.decide(req));
        assertTrue(ex.getMessage().contains("BLOCK"));
    }

    /**
     * 场景：温度合规通过但海关不匹配 → 自动降级 CONDITIONAL_RELEASE，
     * task 状态联动为 CONDITIONAL（不强制要求人工传 BLOCK）。
     */
    @Test
    void decide_tempOkButCustomsNotOk_downgradesToConditional() {
        whenAuditPass();
        when(findingRepo.findByAuditId(1L)).thenReturn(Collections.emptyList());
        whenInspectionAllPass();

        // 海关有 1 条 UNMATCHED
        CustomsDeclaration dec = new CustomsDeclaration();
        dec.setId(50L);
        dec.setTaskId(100L);
        when(customsRepo.findByTaskId(100L)).thenReturn(new ArrayList<>(Arrays.asList(dec)));
        CustomsBatchItem item = new CustomsBatchItem();
        item.setMatchStatus("UNMATCHED");
        when(customsItemRepo.findByDeclarationId(50L)).thenReturn(new ArrayList<>(Arrays.asList(item)));

        DecisionRequest req = new DecisionRequest();
        req.setTaskNo("T-FDA-001");
        req.setDecision(Constants.DECISION_RELEASE);
        req.setDecidedBy("QA-01");

        Map<String, Object> result = decisionService.decide(req);

        assertEquals(Constants.DECISION_CONDITIONAL, result.get("decision"));
        assertEquals(Constants.TASK_CONDITIONAL, result.get("taskStatus"));
    }

    /**
     * 场景：用户主动选 CONDITIONAL_RELEASE → 直接落库为条件放行，task 状态 CONDITIONAL。
     */
    @Test
    void decide_explicitConditional_landsAsConditional() {
        whenAuditPass();
        when(findingRepo.findByAuditId(1L)).thenReturn(Collections.emptyList());
        whenCustomsMatched();
        whenInspectionAllPass();

        DecisionRequest req = new DecisionRequest();
        req.setTaskNo("T-FDA-001");
        req.setDecision(Constants.DECISION_CONDITIONAL);
        req.setDecidedBy("QA-01");
        req.setComment("审慎放行");

        Map<String, Object> result = decisionService.decide(req);

        assertEquals(Constants.DECISION_CONDITIONAL, result.get("decision"));
        assertEquals(Constants.TASK_CONDITIONAL, result.get("taskStatus"));
    }

    /**
     * 场景：飞检时 audit_basis 必须能直接看到 MAJOR finding 数量与温度区间。
     */
    @Test
    void decide_basisContainsFindingSeverityAndTempRange() {
        AuditReport report = new AuditReport();
        report.setId(1L);
        report.setTaskId(100L);
        report.setStatus(Constants.AUDIT_REVIEW);
        report.setFindingCount(2);
        when(auditRepo.findByTaskIdOrderByStartedAtDesc(100L))
                .thenReturn(new ArrayList<>(Arrays.asList(report)));
        when(findingRepo.findByAuditId(1L)).thenReturn(Arrays.asList(
                finding(10L, "R-RANGE-001", Constants.SEV_MAJOR, "9.5", "10.2"),
                finding(11L, "R-RANGE-001", Constants.SEV_MAJOR, "10.1", "10.8")
        ));
        whenCustomsMatched();
        whenInspectionAllPass();

        DecisionRequest req = new DecisionRequest();
        req.setTaskNo("T-FDA-001");
        req.setDecision(Constants.DECISION_CONDITIONAL);
        req.setDecidedBy("QA-01");
        req.setAcknowledgeFindings(Arrays.asList("10", "11"));
        req.setComment("已知风险附条件放行");

        Map<String, Object> result = decisionService.decide(req);

        @SuppressWarnings("unchecked")
        Map<String, Object> basis = (Map<String, Object>) result.get("basis");
        @SuppressWarnings("unchecked")
        Map<String, Integer> sev = (Map<String, Integer>) basis.get("findingSeverity");
        assertNotNull(sev, "basis.findingSeverity 必须存在");
        assertEquals(2, sev.get(Constants.SEV_MAJOR), "MAJOR 数量必须为 2");
        assertEquals(0, sev.get(Constants.SEV_CRITICAL));

        String tempRange = (String) basis.get("temperatureRange");
        assertNotNull(tempRange, "basis.temperatureRange 必须存在");
        assertTrue(tempRange.contains("9.5"), "应包含最低温度");
        assertTrue(tempRange.contains("10.8"), "应包含最高温度");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fs = (List<Map<String, Object>>) basis.get("findings");
        assertEquals(2, fs.size(), "findings 明细必须为 2 条");
        assertEquals(Constants.DECISION_CONDITIONAL, basis.get("finalDecision"));
    }

    /**
     * 场景：BLOCK→PASS 仅间隔 5 分钟（整改观察窗 30 分钟内）→ 形式合规绕过被 GxP 拦截。
     * 模拟"审计员补录 3 条合规样本触发重新审计拿到 PASS"绕过路径。
     */
    @Test
    void decide_blockToPassWithinRemediationWindow_isRejectedByGxpHistoryCheck() {
        // 两条审计：先 BLOCK（5 分钟前结束），再 PASS（刚刚启动）
        AuditReport block = new AuditReport();
        block.setId(1L);
        block.setTaskId(100L);
        block.setStatus(Constants.AUDIT_BLOCK);
        block.setRuleVersion(2);
        block.setStartedAt(OffsetDateTime.parse("2026-07-20T00:00:00Z"));
        block.setFinishedAt(OffsetDateTime.parse("2026-07-20T01:00:00Z"));
        block.setFindingCount(3);

        AuditReport pass = new AuditReport();
        pass.setId(2L);
        pass.setTaskId(100L);
        pass.setStatus(Constants.AUDIT_PASS);
        pass.setRuleVersion(2);
        pass.setStartedAt(OffsetDateTime.parse("2026-07-20T01:05:00Z"));  // 仅 5 分钟后
        pass.setFinishedAt(OffsetDateTime.parse("2026-07-20T01:06:00Z"));
        pass.setFindingCount(0);

        // findByTaskIdOrderByStartedAtDesc 返回 desc 顺序：pass 在前、block 在后
        when(auditRepo.findByTaskIdOrderByStartedAtDesc(100L))
                .thenReturn(new ArrayList<>(Arrays.asList(pass, block)));
        when(findingRepo.findByAuditId(2L)).thenReturn(Collections.emptyList());
        whenCustomsMatched();
        whenInspectionAllPass();

        DecisionRequest req = new DecisionRequest();
        req.setTaskNo("T-FDA-001");
        req.setDecision(Constants.DECISION_RELEASE);
        req.setDecidedBy("QA-01");
        req.setComment("补录样本后快速重审拿到 PASS，要求放行");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> decisionService.decide(req),
                "BLOCK→PASS 间隔 5 分钟属于形式合规绕过，必须被 GxP 硬拦截");
        assertTrue(ex.getMessage().contains("GxP审计历史差异校验拦截"),
                "异常消息必须明确是 GxP审计历史差异校验拦截");
        assertTrue(ex.getMessage().contains("fast re-audit after BLOCK"),
                "异常消息必须明确是 fast re-audit 拦截原因");
        assertTrue(ex.getMessage().contains("elapsed=300"),
                "异常消息必须给出实际间隔秒数（5*60=300）");
    }

    /**
     * 场景：BLOCK→PASS 间隔 60 分钟（超过 30 分钟整改观察窗）+ 规则版本未降级 → 允许 RELEASE。
     * 证明修复没有"误伤"真实整改后的合法放行。
     */
    @Test
    void decide_blockToPassAfterRemediationWindow_releaseAccepted() {
        AuditReport block = new AuditReport();
        block.setId(1L);
        block.setTaskId(100L);
        block.setStatus(Constants.AUDIT_BLOCK);
        block.setRuleVersion(2);
        block.setStartedAt(OffsetDateTime.parse("2026-07-20T00:00:00Z"));
        block.setFinishedAt(OffsetDateTime.parse("2026-07-20T01:00:00Z"));
        block.setFindingCount(3);

        AuditReport pass = new AuditReport();
        pass.setId(2L);
        pass.setTaskId(100L);
        pass.setStatus(Constants.AUDIT_PASS);
        pass.setRuleVersion(2);  // 版本未降级
        pass.setStartedAt(OffsetDateTime.parse("2026-07-20T02:00:00Z"));  // 60 分钟后
        pass.setFinishedAt(OffsetDateTime.parse("2026-07-20T02:01:00Z"));
        pass.setFindingCount(0);

        when(auditRepo.findByTaskIdOrderByStartedAtDesc(100L))
                .thenReturn(new ArrayList<>(Arrays.asList(pass, block)));
        when(findingRepo.findByAuditId(2L)).thenReturn(Collections.emptyList());
        whenCustomsMatched();
        whenInspectionAllPass();

        DecisionRequest req = new DecisionRequest();
        req.setTaskNo("T-FDA-001");
        req.setDecision(Constants.DECISION_RELEASE);
        req.setDecidedBy("QA-01");
        req.setComment("整改完成后 60 分钟重审通过");

        Map<String, Object> result = decisionService.decide(req);

        assertEquals(Constants.DECISION_RELEASE, result.get("decision"));
        assertEquals(Constants.TASK_RELEASED, result.get("taskStatus"));
        @SuppressWarnings("unchecked")
        Map<String, Object> basis = (Map<String, Object>) result.get("basis");
        assertEquals(true, basis.get("temperatureOk"));
        assertEquals(true, basis.get("hasPriorBlockAudit"));
        assertEquals(3600L, basis.get("blockToPassElapsedSec"));
        assertNull(basis.get("historyRejectReason"));
    }

    /**
     * 场景：BLOCK→PASS 间隔足够但最新审计的 rule_version 低于 BLOCK（规则降级绕过）→ 拒绝 RELEASE。
     * 模拟"通过下调规则版本拿到 PASS"的形式合规绕过。
     */
    @Test
    void decide_blockToPassWithRuleVersionDowngrade_isRejectedByGxpHistoryCheck() {
        AuditReport block = new AuditReport();
        block.setId(1L);
        block.setTaskId(100L);
        block.setStatus(Constants.AUDIT_BLOCK);
        block.setRuleVersion(3);  // 旧版本严格规则
        block.setStartedAt(OffsetDateTime.parse("2026-07-20T00:00:00Z"));
        block.setFinishedAt(OffsetDateTime.parse("2026-07-20T01:00:00Z"));
        block.setFindingCount(3);

        AuditReport pass = new AuditReport();
        pass.setId(2L);
        pass.setTaskId(100L);
        pass.setStatus(Constants.AUDIT_PASS);
        pass.setRuleVersion(2);  // 降到更宽松的规则版本
        pass.setStartedAt(OffsetDateTime.parse("2026-07-20T03:00:00Z"));  // 2 小时后，间隔足够
        pass.setFinishedAt(OffsetDateTime.parse("2026-07-20T03:01:00Z"));
        pass.setFindingCount(0);

        when(auditRepo.findByTaskIdOrderByStartedAtDesc(100L))
                .thenReturn(new ArrayList<>(Arrays.asList(pass, block)));
        when(findingRepo.findByAuditId(2L)).thenReturn(Collections.emptyList());
        whenCustomsMatched();
        whenInspectionAllPass();

        DecisionRequest req = new DecisionRequest();
        req.setTaskNo("T-FDA-001");
        req.setDecision(Constants.DECISION_RELEASE);
        req.setDecidedBy("QA-01");
        req.setComment("通过降低规则版本拿到 PASS，要求放行");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> decisionService.decide(req),
                "规则版本降级必须被 GxP 硬拦截");
        assertTrue(ex.getMessage().contains("rule_version downgrade"),
                "异常消息必须明确是规则版本降级拦截");
        assertTrue(ex.getMessage().contains("latest=2"),
                "异常消息必须给出最新规则版本");
        assertTrue(ex.getMessage().contains("priorBlock=3"),
                "异常消息必须给出 BLOCK 时的规则版本");
    }

    /**
     * 场景：BLOCK→PASS 历史异常但用户主动选 CONDITIONAL_RELEASE → 仍允许条件放行。
     * 修复不阻塞合规的"承认风险附条件放行"路径。
     */
    @Test
    void decide_blockToPassFast_conditionalStillAllowed() {
        AuditReport block = new AuditReport();
        block.setId(1L);
        block.setTaskId(100L);
        block.setStatus(Constants.AUDIT_BLOCK);
        block.setRuleVersion(2);
        block.setStartedAt(OffsetDateTime.parse("2026-07-20T00:00:00Z"));
        block.setFinishedAt(OffsetDateTime.parse("2026-07-20T01:00:00Z"));
        block.setFindingCount(3);

        AuditReport pass = new AuditReport();
        pass.setId(2L);
        pass.setTaskId(100L);
        pass.setStatus(Constants.AUDIT_PASS);
        pass.setRuleVersion(2);
        pass.setStartedAt(OffsetDateTime.parse("2026-07-20T01:05:00Z"));
        pass.setFinishedAt(OffsetDateTime.parse("2026-07-20T01:06:00Z"));
        pass.setFindingCount(0);

        when(auditRepo.findByTaskIdOrderByStartedAtDesc(100L))
                .thenReturn(new ArrayList<>(Arrays.asList(pass, block)));
        when(findingRepo.findByAuditId(2L)).thenReturn(Collections.emptyList());
        whenCustomsMatched();
        whenInspectionAllPass();

        DecisionRequest req = new DecisionRequest();
        req.setTaskNo("T-FDA-001");
        req.setDecision(Constants.DECISION_CONDITIONAL);
        req.setDecidedBy("QA-01");
        req.setComment("历史 BLOCK 待核实，附条件放行");

        Map<String, Object> result = decisionService.decide(req);

        assertEquals(Constants.DECISION_CONDITIONAL, result.get("decision"));
        assertEquals(Constants.TASK_CONDITIONAL, result.get("taskStatus"));
        @SuppressWarnings("unchecked")
        Map<String, Object> basis = (Map<String, Object>) result.get("basis");
        assertEquals(false, basis.get("temperatureOk"));
        assertEquals(true, basis.get("hasPriorBlockAudit"));
        assertNotNull(basis.get("historyRejectReason"));
        assertTrue(basis.get("historyRejectReason").toString().contains("fast re-audit"));
    }

    private void whenAuditPass() {
        AuditReport report = new AuditReport();
        report.setId(1L);
        report.setTaskId(100L);
        report.setStatus(Constants.AUDIT_PASS);
        report.setFindingCount(0);
        when(auditRepo.findByTaskIdOrderByStartedAtDesc(100L))
                .thenReturn(new ArrayList<>(Arrays.asList(report)));
    }

    private void whenCustomsMatched() {
        CustomsDeclaration dec = new CustomsDeclaration();
        dec.setId(50L);
        dec.setTaskId(100L);
        when(customsRepo.findByTaskId(100L)).thenReturn(new ArrayList<>(Arrays.asList(dec)));
        CustomsBatchItem item = new CustomsBatchItem();
        item.setMatchStatus("MATCHED");
        when(customsItemRepo.findByDeclarationId(50L)).thenReturn(new ArrayList<>(Arrays.asList(item)));
    }

    private void whenInspectionAllPass() {
        InspectionReport r = new InspectionReport();
        r.setTaskId(100L);
        r.setConclusion("PASS");
        when(inspectionRepo.findByTaskId(100L)).thenReturn(new ArrayList<>(Arrays.asList(r)));
    }

    private AuditFinding finding(long id, String code, String severity, String minT, String maxT) {
        AuditFinding f = new AuditFinding();
        f.setId(id);
        f.setAuditId(1L);
        f.setRuleCode(code);
        f.setSeverity(severity);
        f.setAction(Constants.ACTION_REVIEW);
        f.setTimeRangeStart(OffsetDateTime.parse("2026-07-20T00:10:00Z"));
        f.setTimeRangeEnd(OffsetDateTime.parse("2026-07-20T00:20:00Z"));
        f.setTemperatureMin(new BigDecimal(minT));
        f.setTemperatureMax(new BigDecimal(maxT));
        f.setDescription("温度越界 " + minT + " ~ " + maxT);
        f.setEvidence("{\"samples\":1}");
        return f;
    }
}
