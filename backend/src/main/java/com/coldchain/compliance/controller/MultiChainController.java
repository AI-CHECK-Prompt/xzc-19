package com.coldchain.compliance.controller;

import com.coldchain.compliance.engine.MultiChainCoordinationEngine;
import com.coldchain.compliance.repository.MultiChainComplianceRepository;
import com.coldchain.compliance.repository.TransportTaskRepository;
import com.coldchain.compliance.service.OperationLogService;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 多式联运全链协同 API。
 * <p>
 * 核心接口：POST /api/multichain/coordinate/{taskId}
 * 触发全链审计 → 段贡献聚合 → 影响剂量估算 → 责任段定位 → 整改工单 → 监管报告。
 */
@RestController
@RequestMapping("/api/multichain")
@RequiredArgsConstructor
public class MultiChainController {

    private final MultiChainCoordinationEngine engine;
    private final TransportTaskRepository taskRepo;
    private final MultiChainComplianceRepository complianceRepo;
    private final OperationLogService opLogService;

    /** 触发全链协同评估 */
    @PostMapping("/coordinate/{taskId}")
    public Map<String, Object> coordinate(@PathVariable Long taskId, HttpServletRequest req) {
        Map<String, Object> result = engine.coordinate(taskId);
        opLogService.logAsync("AUDITOR", "AUDITOR", "MULTI_CHAIN_COORDINATE", "TASK",
                "taskId=" + taskId,
                "status=" + ((Map) result.get("overall")).get("status"),
                "OK", req.getRemoteAddr(), req.getHeader("User-Agent"));
        return result;
    }

    /** 拉取已持久化的多链合规结论 */
    @GetMapping("/{taskId}")
    public Map<String, Object> getCompliance(@PathVariable Long taskId) {
        return complianceRepo.findByTaskId(taskId).map(mc -> {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("taskId", mc.getTaskId());
            vo.put("overallStatus", mc.getOverallStatus());
            vo.put("overallDecision", mc.getOverallDecision());
            vo.put("segmentCount", mc.getSegmentCount());
            vo.put("criticalSegments", mc.getCriticalSegments() == null ? null : JsonUtil.toList(mc.getCriticalSegments()));
            vo.put("contributions", mc.getContributionJson() == null ? null : JsonUtil.toList(mc.getContributionJson()));
            vo.put("summaryText", mc.getSummaryText());
            vo.put("createdAt", mc.getCreatedAt());
            return vo;
        }).orElse(null);
    }
}
