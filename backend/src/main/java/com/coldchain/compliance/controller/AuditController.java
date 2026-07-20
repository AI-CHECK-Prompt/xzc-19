package com.coldchain.compliance.controller;

import com.coldchain.compliance.dto.AuditSummary;
import com.coldchain.compliance.dto.DecisionRequest;
import com.coldchain.compliance.engine.RuleEngine;
import com.coldchain.compliance.repository.TransportTaskRepository;
import com.coldchain.compliance.service.DecisionService;
import com.coldchain.compliance.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final RuleEngine ruleEngine;
    private final DecisionService decisionService;
    private final TransportTaskRepository taskRepo;
    private final OperationLogService opLogService;

    @PostMapping("/run/{taskNo}")
    public AuditSummary run(@PathVariable String taskNo, HttpServletRequest req) {
        Long id = taskRepo.findByTaskNo(taskNo)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskNo))
                .getId();
        AuditSummary s = ruleEngine.audit(id);
        opLogService.logAsync("AUDITOR", "AUDITOR", "RUN_AUDIT", "TASK",
                taskNo, "auditId=" + s.getAuditId() + " status=" + s.getStatus(),
                s.getStatus(), req.getRemoteAddr(), req.getHeader("User-Agent"));
        return s;
    }

    @PostMapping("/decide")
    public Map<String, Object> decide(@RequestBody DecisionRequest req, HttpServletRequest httpReq) {
        return decisionService.decide(req);
    }
}
