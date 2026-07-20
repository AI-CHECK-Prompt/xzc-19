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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 回归测试：RuleEngine.checkCumulative 循环终止边界。
 * <p>
 * 飞检场景：运输末段（最后 2 小时窗口内）持续 35 分钟温度 > 8℃，按规则应出
 * CRITICAL finding 并自动拦截放行。原实现中循环只在"状态切换到正常"时触发
 * maybeEmitCumulative，循环结束时若末段仍处于超温状态，aboveStart 非空但
 * 循环结束没触发 → 末段 finding 永远丢失 → 自动放行。
 * <p>
 * 历史 Bug：checkCumulative 缺少循环结束的兜底分支，末段超温未生成 finding。
 */
class RuleEngineCumulativeTest {

    @TempDir
    Path tmpKeyDir;

    private RuleEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        // 仅占位，RuleEngine 依赖项通过 mock 满足，checkCumulative 私有方法不调用这些 repo
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
     * 核心场景：中段一次 20 分钟超温 + 末段 35 分钟持续超温（任务到最后一个样本
     * 仍未恢复正常）。阈值 15 分钟，两段均应被捕获。修复前末段 finding 丢失。
     */
    @Test
    void cumulative_lastSegmentStillHot_emitsFinding() throws Exception {
        TransportTask task = new TransportTask();
        task.setId(1L);
        task.setTaskNo("T-001");

        ComplianceRule rule = new ComplianceRule();
        rule.setCode("R-CUM-15");
        rule.setName("持续超温 15 分钟");
        rule.setCategory("CUMULATIVE");
        rule.setSeverity("CRITICAL");
        rule.setAction("BLOCK");
        rule.setExpression("{\"max_above_minutes\":15,\"max_below_minutes\":15,\"min_c\":2,\"max_c\":8}");

        // 构造样本：T0 起前 30 分钟正常 (4℃)，随后 20 分钟超温 (10℃)，恢复 5 分钟，
        // 再 35 分钟超温到末条（任务结束前）。
        OffsetDateTime t0 = OffsetDateTime.parse("2026-07-20T00:00:00Z");
        List<TempSample> samples = new ArrayList<>();
        // 前 30 分钟正常：4℃，每 1 分钟一个点
        for (int i = 0; i < 30; i++) {
            samples.add(sample(t0.plusMinutes(i), "4.0"));
        }
        // 中段 20 分钟超温：10℃，从 30 到 50 分钟
        for (int i = 30; i < 50; i++) {
            samples.add(sample(t0.plusMinutes(i), "10.0"));
        }
        // 恢复 5 分钟：4℃，从 50 到 55 分钟
        for (int i = 50; i < 55; i++) {
            samples.add(sample(t0.plusMinutes(i), "4.0"));
        }
        // 末段 35 分钟持续超温到末条：10℃，从 55 到 90 分钟
        for (int i = 55; i < 90; i++) {
            samples.add(sample(t0.plusMinutes(i), "10.0"));
        }

        List<AuditFinding> out = invokeCheckCumulative(task, samples, rule);

        // 修复后必须产出 2 条 finding：中段 20 分钟 + 末段 35 分钟
        assertEquals(2, out.size(),
                "末段持续超温在循环结束时未触发 maybeEmitCumulative，finding 缺失");

        AuditFinding mid = out.get(0);
        assertTrue(mid.getDescription().contains("20"), "中段应记录 20 分钟");
        assertEquals(t0.plusMinutes(30), mid.getTimeRangeStart());
        assertEquals(t0.plusMinutes(50), mid.getTimeRangeEnd());

        AuditFinding tail = out.get(1);
        assertTrue(tail.getDescription().contains("35"), "末段应记录 35 分钟");
        assertEquals(t0.plusMinutes(55), tail.getTimeRangeStart());
        // 末段 end = 最后一条样本时间（任务结束点）
        assertEquals(t0.plusMinutes(89), tail.getTimeRangeEnd());
        assertEquals("CRITICAL", tail.getSeverity());
        assertEquals("BLOCK", tail.getAction());
    }

    /**
     * 对称性测试：末段持续低温（< 2℃）也应被兜底捕获。
     */
    @Test
    void cumulative_lastSegmentStillCold_emitsFinding() throws Exception {
        TransportTask task = new TransportTask();
        task.setId(2L);
        task.setTaskNo("T-002");

        ComplianceRule rule = new ComplianceRule();
        rule.setCode("R-CUM-15");
        rule.setName("持续低温 15 分钟");
        rule.setCategory("CUMULATIVE");
        rule.setSeverity("CRITICAL");
        rule.setAction("BLOCK");
        rule.setExpression("{\"max_above_minutes\":15,\"max_below_minutes\":15,\"min_c\":2,\"max_c\":8}");

        OffsetDateTime t0 = OffsetDateTime.parse("2026-07-20T00:00:00Z");
        List<TempSample> samples = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            samples.add(sample(t0.plusMinutes(i), "4.0"));
        }
        // 末段 40 分钟持续低温到末条：0℃，从 30 到 70 分钟
        for (int i = 30; i < 70; i++) {
            samples.add(sample(t0.plusMinutes(i), "0.0"));
        }

        List<AuditFinding> out = invokeCheckCumulative(task, samples, rule);

        assertEquals(1, out.size());
        AuditFinding tail = out.get(0);
        assertTrue(tail.getDescription().contains("below"));
        assertTrue(tail.getDescription().contains("39") || tail.getDescription().contains("40"),
                "末段低温持续时长应接近 40 分钟");
        assertEquals(t0.plusMinutes(30), tail.getTimeRangeStart());
        assertEquals(t0.plusMinutes(69), tail.getTimeRangeEnd());
    }

    /**
     * 边界：末段超温但累计时长 < 阈值（15 分钟），不应误报。
     */
    @Test
    void cumulative_lastSegmentShort_noFinding() throws Exception {
        TransportTask task = new TransportTask();
        task.setId(3L);
        task.setTaskNo("T-003");

        ComplianceRule rule = new ComplianceRule();
        rule.setCode("R-CUM-15");
        rule.setCategory("CUMULATIVE");
        rule.setSeverity("CRITICAL");
        rule.setAction("BLOCK");
        rule.setExpression("{\"max_above_minutes\":15,\"max_below_minutes\":15,\"min_c\":2,\"max_c\":8}");

        OffsetDateTime t0 = OffsetDateTime.parse("2026-07-20T00:00:00Z");
        List<TempSample> samples = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            samples.add(sample(t0.plusMinutes(i), "4.0"));
        }
        // 末段仅 10 分钟超温（< 15 分钟阈值）
        for (int i = 30; i < 40; i++) {
            samples.add(sample(t0.plusMinutes(i), "10.0"));
        }

        List<AuditFinding> out = invokeCheckCumulative(task, samples, rule);

        assertEquals(0, out.size(),
                "末段未达阈值不应误报 finding");
    }

    @SuppressWarnings("unchecked")
    private List<AuditFinding> invokeCheckCumulative(TransportTask task,
                                                      List<TempSample> samples,
                                                      ComplianceRule rule) throws Exception {
        Method m = RuleEngine.class.getDeclaredMethod("checkCumulative",
                TransportTask.class, List.class, ComplianceRule.class);
        m.setAccessible(true);
        return (List<AuditFinding>) m.invoke(engine, task, samples, rule);
    }

    private TempSample sample(OffsetDateTime at, String temp) {
        TempSample s = new TempSample();
        s.setDeviceNo("DEV-1");
        s.setTaskId(1L);
        s.setSampleAt(at);
        s.setTemperature(new BigDecimal(temp));
        s.setDoorOpen(false);
        s.setSeqNo(at.getMinute() + at.getHour() * 60L);
        s.setPayloadHash("dummy");
        s.setSignature("dummy");
        return s;
    }

    // 抑制 JsonUtil 未使用告警（保留导入以匹配实际依赖图）
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_JSON = JsonUtil.class;
}
