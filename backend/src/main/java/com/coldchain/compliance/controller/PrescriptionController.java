package com.coldchain.compliance.controller;

import com.coldchain.compliance.service.ExceptionPrescriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 异常处置预案库。按剂型+异常类型检索，展示决策依据（阈值/动作/责任/时效/监管报告）。
 */
@RestController
@RequestMapping("/api/prescriptions")
@RequiredArgsConstructor
public class PrescriptionController {

    private final ExceptionPrescriptionService service;

    /** 全部预案 */
    @GetMapping
    public List<Map<String, Object>> list() { return service.listAll(); }

    /** 按剂型列预案 */
    @GetMapping("/by-form/{dosageForm}")
    public List<Map<String, Object>> byForm(@PathVariable String dosageForm) {
        return service.listByDosageForm(dosageForm);
    }

    /** 按剂型+异常类型查具体预案 */
    @GetMapping("/lookup")
    public Map<String, Object> lookup(@RequestParam String dosageForm, @RequestParam String exceptionType) {
        return service.getByFormAndType(dosageForm, exceptionType);
    }
}
