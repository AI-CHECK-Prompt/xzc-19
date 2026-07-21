package com.coldchain.compliance.engine;

import com.coldchain.compliance.entity.AuditFinding;
import com.coldchain.compliance.entity.DoseImpactRule;
import com.coldchain.compliance.entity.DrugBatch;
import com.coldchain.compliance.repository.DoseImpactRuleRepository;
import com.coldchain.compliance.util.Constants;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 影响剂量估算器：把"异常"转换为"受影响药品剂量"。
 * <p>
 * 算法：dose = base_factor * (duration_min × per_minute_factor + degrees × per_degree_factor + 1)
 * 适用于 OVERHEAT/UNDERCOOL/DOOR_OPEN/DEVICE_OFFLINE/SAMPLING_GAP/TRACK_DEVIATION。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoseImpactCalculator {

    private final DoseImpactRuleRepository ruleRepo;

    public static class DoseResult {
        public double affectedDoseRatio;     // 0~1，占批次总量的比例
        public int estimatedAffectedQty;     // 估算出受影响剂数
        public String exceptionType;
        public String dosageForm;
        public double baseFactor;
        public double perMinuteFactor;
        public double perDegreeFactor;
        public Map<String, Object> detail;
    }

    /**
     * @param finding  审计证据
     * @param batches  该订单的药品批次列表
     * @param exceptionType 异常类型
     * @param dosageForm 剂型
     * @param durationMin 持续时长（分钟，门/超温/断电/采样中断）
     * @param degrees 偏离度数（超温/低温的℃）
     */
    public DoseResult calculate(AuditFinding finding, List<DrugBatch> batches,
                                String exceptionType, String dosageForm,
                                double durationMin, double degrees) {
        DoseResult r = new DoseResult();
        r.exceptionType = exceptionType;
        r.dosageForm = dosageForm;
        r.detail = new LinkedHashMap<>();

        Optional<DoseImpactRule> opt = ruleRepo.findByDosageFormAndExceptionType(dosageForm, exceptionType);
        if (!opt.isPresent()) {
            // 兜底：找通用规则
            opt = ruleRepo.findByDosageFormAndExceptionType(Constants.FORM_COLD, exceptionType);
        }
        if (!opt.isPresent()) {
            r.affectedDoseRatio = 0.0;
            r.estimatedAffectedQty = 0;
            r.detail.put("fallback", "no rule found");
            return r;
        }
        DoseImpactRule rule = opt.get();
        r.baseFactor = rule.getBaseFactor().doubleValue();
        r.perMinuteFactor = rule.getPerMinuteFactor().doubleValue();
        r.perDegreeFactor = rule.getPerDegreeFactor().doubleValue();

        // 剂量比例计算
        double ratio = rule.getBaseFactor().doubleValue()
                * (1 + rule.getPerMinuteFactor().doubleValue() * Math.max(0, durationMin)
                   + rule.getPerDegreeFactor().doubleValue() * Math.max(0, degrees));
        if (ratio > 1.0) ratio = 1.0;          // 上限
        if (ratio < 0.0) ratio = 0.0;
        r.affectedDoseRatio = round4(ratio);

        // 受影响剂数 = 批次总量 × ratio
        int totalQty = 0;
        for (DrugBatch b : batches) totalQty += b.getQuantity() == null ? 0 : b.getQuantity();
        r.estimatedAffectedQty = (int) Math.ceil(totalQty * r.affectedDoseRatio);

        r.detail.put("durationMin", round2(durationMin));
        r.detail.put("degrees", round2(degrees));
        r.detail.put("baseFactor", r.baseFactor);
        r.detail.put("perMinuteFactor", r.perMinuteFactor);
        r.detail.put("perDegreeFactor", r.perDegreeFactor);
        r.detail.put("affectedDoseRatio", r.affectedDoseRatio);
        r.detail.put("totalQty", totalQty);
        r.detail.put("estimatedAffectedQty", r.estimatedAffectedQty);
        r.detail.put("formula", rule.getFormula());
        r.detail.put("evidenceJson", finding == null ? null : finding.getEvidence());
        log.info("【剂量估算】exceptionType={} form={} ratio={} qty={}",
                exceptionType, dosageForm, r.affectedDoseRatio, r.estimatedAffectedQty);
        return r;
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
    private double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
