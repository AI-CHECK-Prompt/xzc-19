package com.coldchain.compliance.controller;

import com.coldchain.compliance.dto.ReplayResponse;
import com.coldchain.compliance.entity.ComplianceRule;
import com.coldchain.compliance.repository.ComplianceRuleRepository;
import com.coldchain.compliance.service.ReplayService;
import com.coldchain.compliance.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReplayController {

    private final ReplayService replayService;
    private final SearchService searchService;
    private final ComplianceRuleRepository ruleRepo;

    @GetMapping("/replay/{taskNo}")
    public ReplayResponse replay(@PathVariable String taskNo) {
        return replayService.replay(taskNo);
    }

    @GetMapping("/search/tasks")
    public Map<String, Object> searchTasks(
            @RequestParam(required = false) String taskNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return searchService.searchTasks(taskNo, status, from, to);
    }

    @GetMapping("/search/batches")
    public Map<String, Object> searchBatches(@RequestParam(required = false) String dosageForm,
                                              @RequestParam(required = false) String batchNo) {
        return searchService.searchBatches(dosageForm, batchNo);
    }

    @GetMapping("/rules")
    public List<ComplianceRule> rules() {
        return ruleRepo.findAll();
    }
}
