package com.coldchain.compliance.engine;

import com.coldchain.compliance.entity.AuditFinding;
import com.coldchain.compliance.entity.ComplianceRule;
import com.coldchain.compliance.entity.TempSample;
import com.coldchain.compliance.entity.TransportTask;
import com.coldchain.compliance.repository.AuditFindingRepository;
import com.coldchain.compliance.repository.AuditReportRepository;
import com.coldchain.compliance.repository.ComplianceRuleRepository;
import com.coldchain.compliance.repository.DrugBatchRepository;
import com.coldchain.compliance.repository.TempSampleRepository;
import com.coldchain.compliance.repository.TrackPointRepository;
import com.coldchain.compliance.repository.TransportTaskRepository;
import com.coldchain.compliance.security.SignatureUtil;
import com.coldchain.compliance.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 回归测试：RuleEngine.checkContinuity 多设备冗余架构下的设备隔离。
 * <p>
 * 飞检场景：单 task 挂主 + 备两台温控记录器，按规约每 5 分钟采一次。
 * 原实现把所有 temp_samples 按 sampleAt 排序后做"相邻时间间隔"检查，忽略
 * deviceNo 维度，导致主备切换时刻被误判为连续性中断，CONTINUITY 误报率
 * 高达 30%~50%，监管抽查时被反复打回。
 * <p>
 * 修复后：仅当同一设备内相邻样本时间间隔 > 阈值时，才出具 CONTINUITY finding。
 */
class RuleEngineContinuityTest {

    @TempDir
    Path tmpKeyDir;

    private RuleEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        SignatureUtil sig = new SignatureUtil();
        java.lang.reflect.Field f = SignatureUtil.class.getDeclaredField("keyDir");
        f.setAccessible(true);
        f.set(sig, tmpKeyDir.toString());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(sig, "init");

        engine = new RuleEngine(
                mock(ComplianceRuleRepository.class),
                mock(TempSampleRepository.class),
                mock(TrackPointRepository.class),
                mock(DrugBatchRepository.class),
                mock(AuditReportRepository.class),
                mock(AuditFindingRepository.class),
                mock(TransportTaskRepository.class),
                sig);
    }

    /**
     * 核心场景：主设备 A 末样本 10:00:00、备设备 B 首样本 10:05:30。
     * 修复前：被误判为 5 分 30 秒间隔（330s > 300s 阈值）出具 CONTINUITY finding。
     * 修复后：两设备各自采样间隔均合规，不应出具任何 finding。
     */
    @Test
    void continuity_crossDeviceGap_noFalsePositive() throws Exception {
        TransportTask task = new TransportTask();
        task.setId(10L);
        task.setTaskNo("T-010");

        ComplianceRule rule = new ComplianceRule();
        rule.setCode("R-CONT-300");
        rule.setName("采样连续性 5 分钟");
        rule.setCategory("CONTINUITY");
        rule.setSeverity("MAJOR");
        rule.setAction("REVIEW");
        rule.setExpression("{\"max_gap_sec\":300}");

        OffsetDateTime t0 = OffsetDateTime.parse("2026-07-20T00:00:00Z");
        List<TempSample> samples = new ArrayList<>();

        // 主设备 A：09:00 ~ 10:00，每 5 分钟一条，最后一条 10:00:00
        for (int i = 0; i <= 12; i++) {
            samples.add(sample("DEV-A", t0.plusMinutes(i * 5L), "4.0"));
        }
        // 备设备 B：10:05:30 ~ 11:00:30，每 5 分钟一条，第一条 10:05:30
        // 注意：B 的首样本与 A 的末样本全局相邻，但属于不同设备
        for (int i = 0; i <= 11; i++) {
            samples.add(sample("DEV-B", t0.plusMinutes(5 * 60 + 30 + i * 5L), "4.0"));
        }

        List<AuditFinding> out = invokeCheckContinuity(task, samples, rule);

        assertEquals(0, out.size(),
                "主备设备切换时刻不应被误判为连续性中断");
    }

    /**
     * 同设备内真实中断：设备 A 在 10:00:00 之后停采，下一条 10:20:00 出现
     * （间隔 20 分钟 = 1200s > 300s 阈值），应正确出具 finding。
     */
    @Test
    void continuity_sameDeviceRealGap_emitsFinding() throws Exception {
        TransportTask task = new TransportTask();
        task.setId(11L);
        task.setTaskNo("T-011");

        ComplianceRule rule = new ComplianceRule();
        rule.setCode("R-CONT-300");
        rule.setCategory("CONTINUITY");
        rule.setSeverity("MAJOR");
        rule.setAction("REVIEW");
        rule.setExpression("{\"max_gap_sec\":300}");

        OffsetDateTime t0 = OffsetDateTime.parse("2026-07-20T00:00:00Z");
        List<TempSample> samples = new ArrayList<>();

        // 主设备 A：0~20 分钟每 5 分钟一条（最后一条 t0+20min）
        for (int i = 0; i <= 4; i++) {
            samples.add(sample("DEV-A", t0.plusMinutes(i * 5L), "4.0"));
        }
        // 主设备 A：40~60 分钟恢复（停采 20 分钟，间隔 1200s > 300s 阈值）
        for (int i = 0; i <= 4; i++) {
            samples.add(sample("DEV-A", t0.plusMinutes(40 + i * 5L), "4.0"));
        }
        // 备设备 B：全程正常，与 A 时间交错
        for (int i = 0; i <= 12; i++) {
            samples.add(sample("DEV-B", t0.plusMinutes(2 + i * 5L), "4.0"));
        }

        List<AuditFinding> out = invokeCheckContinuity(task, samples, rule);

        assertEquals(1, out.size(), "应仅产出一条 finding");
        AuditFinding f = out.get(0);
        assertTrue(f.getEvidence().contains("DEV-A"),
                "finding 应归属设备 A，evidence=" + f.getEvidence());
        assertTrue(f.getDescription().contains("1200"),
                "finding 应记录 20 分钟（1200 秒）间隔");
        assertNotNull(f.getTimeRangeStart());
        assertNotNull(f.getTimeRangeEnd());
    }

    /**
     * 双设备同时中断：A 和 B 在同一时刻各自出现超阈值间隔，
     * 应各产一条 finding（不合并、不丢失）。
     */
    @Test
    void continuity_bothDevicesGap_emitsTwoFindings() throws Exception {
        TransportTask task = new TransportTask();
        task.setId(12L);
        task.setTaskNo("T-012");

        ComplianceRule rule = new ComplianceRule();
        rule.setCode("R-CONT-300");
        rule.setCategory("CONTINUITY");
        rule.setSeverity("MAJOR");
        rule.setAction("REVIEW");
        rule.setExpression("{\"max_gap_sec\":300}");

        OffsetDateTime t0 = OffsetDateTime.parse("2026-07-20T00:00:00Z");
        List<TempSample> samples = new ArrayList<>();

        // A：09:00、09:05、09:10、09:15、09:20，停采；09:30、09:35 恢复（停 10 分钟）
        for (int i = 0; i <= 4; i++) {
            samples.add(sample("DEV-A", t0.plusMinutes(i * 5L), "4.0"));
        }
        for (int i = 2; i <= 3; i++) {
            samples.add(sample("DEV-A", t0.plusMinutes(30 + i * 5L), "4.0"));
        }
        // B：09:02、09:07、09:12、09:17、09:22，停采；09:35、09:40 恢复（停 13 分钟）
        for (int i = 0; i <= 4; i++) {
            samples.add(sample("DEV-B", t0.plusMinutes(2 + i * 5L), "4.0"));
        }
        for (int i = 2; i <= 3; i++) {
            samples.add(sample("DEV-B", t0.plusMinutes(35 + i * 5L), "4.0"));
        }

        List<AuditFinding> out = invokeCheckContinuity(task, samples, rule);

        assertEquals(2, out.size(), "双设备各停采一次，应产 2 条 finding");
    }

    /**
     * 边界：单设备只采 1 条样本，不应误报。
     */
    @Test
    void continuity_singleSample_noFinding() throws Exception {
        TransportTask task = new TransportTask();
        task.setId(13L);
        task.setTaskNo("T-013");

        ComplianceRule rule = new ComplianceRule();
        rule.setCode("R-CONT-300");
        rule.setCategory("CONTINUITY");
        rule.setExpression("{\"max_gap_sec\":300}");

        OffsetDateTime t0 = OffsetDateTime.parse("2026-07-20T00:00:00Z");
        List<TempSample> samples = new ArrayList<>();
        samples.add(sample("DEV-A", t0, "4.0"));

        List<AuditFinding> out = invokeCheckContinuity(task, samples, rule);

        assertEquals(0, out.size());
    }

    @SuppressWarnings("unchecked")
    private List<AuditFinding> invokeCheckContinuity(TransportTask task,
                                                      List<TempSample> samples,
                                                      ComplianceRule rule) throws Exception {
        Method m = RuleEngine.class.getDeclaredMethod("checkContinuity",
                TransportTask.class, List.class, ComplianceRule.class);
        m.setAccessible(true);
        return (List<AuditFinding>) m.invoke(engine, task, samples, rule);
    }

    private TempSample sample(String deviceNo, OffsetDateTime at, String temp) {
        TempSample s = new TempSample();
        s.setDeviceNo(deviceNo);
        s.setTaskId(1L);
        s.setSampleAt(at);
        s.setTemperature(new BigDecimal(temp));
        s.setDoorOpen(false);
        s.setSeqNo(at.getMinute() + at.getHour() * 60L);
        s.setPayloadHash("dummy");
        s.setSignature("dummy");
        return s;
    }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP_JSON = JsonUtil.class;
}
