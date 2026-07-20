package com.coldchain.compliance.controller;

import com.coldchain.compliance.dto.SelfCheckResult;
import com.coldchain.compliance.service.SelfCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/self-check")
@RequiredArgsConstructor
public class SelfCheckController {

    private final SelfCheckService selfCheckService;

    @GetMapping
    public SelfCheckResult run() {
        return selfCheckService.runAll();
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> r = new HashMap<>();
        r.put("status", "UP");
        r.put("service", "coldchain-compliance");
        r.put("version", "1.0.0");
        return r;
    }
}
