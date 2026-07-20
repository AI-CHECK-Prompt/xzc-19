package com.coldchain.compliance.controller;

import com.coldchain.compliance.dto.CustomsMatchResult;
import com.coldchain.compliance.dto.CustomsParseRequest;
import com.coldchain.compliance.service.CustomsParseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customs")
@RequiredArgsConstructor
public class CustomsController {

    private final CustomsParseService customsParseService;

    @PostMapping("/parse")
    public CustomsMatchResult parseAndMatch(@RequestBody CustomsParseRequest req) {
        return customsParseService.parseAndMatch(req);
    }
}
