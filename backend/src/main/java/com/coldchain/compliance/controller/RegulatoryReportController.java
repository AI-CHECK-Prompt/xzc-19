package com.coldchain.compliance.controller;

import com.coldchain.compliance.entity.RegulatoryReport;
import com.coldchain.compliance.service.RegulatoryReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 监管报告。预案中 regulatoryReport=true 时由协同引擎自动生成。
 */
@RestController
@RequestMapping("/api/regulatory-reports")
@RequiredArgsConstructor
public class RegulatoryReportController {

    private final RegulatoryReportService service;

    @GetMapping
    public List<Map<String, Object>> list() { return service.listAll(); }

    @GetMapping("/by-task/{taskId}")
    public List<Map<String, Object>> byTask(@PathVariable Long taskId) { return service.listByTask(taskId); }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return service.listAll().stream().filter(r -> r.get("id").equals(id)).findFirst().orElse(null);
    }

    @PostMapping("/{id}/submit")
    public Map<String, Object> submit(@PathVariable Long id) {
        RegulatoryReport rr = service.submit(id);
        return service.toVo(rr);
    }
}
