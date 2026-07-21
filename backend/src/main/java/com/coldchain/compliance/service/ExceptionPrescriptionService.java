package com.coldchain.compliance.service;

import com.coldchain.compliance.entity.ExceptionPrescription;
import com.coldchain.compliance.repository.ExceptionPrescriptionRepository;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 异常处置预案库服务。按剂型+异常类型检索，展示决策依据（阈值/动作/责任/时效/是否触发监管报告）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionPrescriptionService {

    private final ExceptionPrescriptionRepository repo;

    public List<Map<String, Object>> listAll() {
        List<ExceptionPrescription> all = repo.findByEnabledTrue();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExceptionPrescription p : all) out.add(toVo(p));
        return out;
    }

    public List<Map<String, Object>> listByDosageForm(String dosageForm) {
        List<ExceptionPrescription> all = repo.findByDosageFormAndEnabledTrue(dosageForm);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExceptionPrescription p : all) out.add(toVo(p));
        return out;
    }

    public Map<String, Object> getByFormAndType(String dosageForm, String exceptionType) {
        List<ExceptionPrescription> list = repo.findByDosageFormAndExceptionTypeAndEnabledTrue(dosageForm, exceptionType);
        if (list.isEmpty()) return null;
        return toVo(list.get(0));
    }

    public ExceptionPrescription getEntity(String dosageForm, String exceptionType) {
        List<ExceptionPrescription> list = repo.findByDosageFormAndExceptionTypeAndEnabledTrue(dosageForm, exceptionType);
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, Object> toVo(ExceptionPrescription p) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", p.getId());
        vo.put("code", p.getCode());
        vo.put("dosageForm", p.getDosageForm());
        vo.put("exceptionType", p.getExceptionType());
        vo.put("title", p.getTitle());
        vo.put("threshold", JsonUtil.toMap(p.getThresholdJson()));
        vo.put("impactRule", p.getImpactRuleJson() == null ? null : JsonUtil.toMap(p.getImpactRuleJson()));
        vo.put("actions", JsonUtil.toList(p.getActionsJson()));
        vo.put("responsibleParty", p.getResponsibleParty());
        vo.put("responseHours", p.getResponseHours());
        vo.put("regulatoryReport", p.getRegulatoryReport());
        vo.put("severity", p.getSeverity());
        vo.put("version", p.getVersion());
        return vo;
    }
}
