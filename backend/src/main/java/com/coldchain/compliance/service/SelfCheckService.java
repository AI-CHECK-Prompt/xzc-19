package com.coldchain.compliance.service;

import com.coldchain.compliance.dto.SelfCheckResult;
import com.coldchain.compliance.dto.SelfCheckResult.CheckItem;
import com.coldchain.compliance.engine.RuleEngine;
import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.security.SignatureUtil;
import com.coldchain.compliance.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 接口自检：覆盖 6 项硬验收指标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfCheckService {

    private final TransportTaskRepository taskRepo;
    private final DrugBatchRepository batchRepo;
    private final TempSampleRepository sampleRepo;
    private final ComplianceRuleRepository ruleRepo;
    private final IngestService ingestService;
    private final RuleEngine ruleEngine;
    private final ExportService exportService;
    private final CustomsParseService customsParseService;
    private final SignatureUtil signatureUtil;
    private final EntityManager em;

    @Value("${coldchain.export.temp-dir:/data/exports}")
    private String tempDir;

    @Transactional
    public SelfCheckResult runAll() {
        SelfCheckResult r = new SelfCheckResult();
        r.setTimestamp(System.currentTimeMillis());
        List<CheckItem> items = new ArrayList<>();
        r.setItems(items);

        items.add(checkDb());
        items.add(checkRuleEngine());
        items.add(checkConcurrentIngest());
        items.add(checkBlockOnOverheat());
        items.add(checkHashChain());
        items.add(checkWorm());
        items.add(checkExports());
        items.add(checkCustoms());

        r.setOverallOk(items.stream().allMatch(CheckItem::isPassed));
        log.info("【自检】总览 passed={} items={}", r.isOverallOk(), items.size());
        return r;
    }

    private CheckItem checkDb() {
        CheckItem c = new CheckItem();
        c.setName("数据库连通");
        c.setCategory("CORE");
        long t0 = System.currentTimeMillis();
        try {
            Object cnt = em.createNativeQuery("SELECT count(*) FROM app_user").getSingleResult();
            c.setPassed(true);
            c.setMessage("app_user 数 = " + cnt);
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("DB 不可达: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkRuleEngine() {
        CheckItem c = new CheckItem();
        c.setName("规则引擎就绪");
        c.setCategory("AUDIT");
        long t0 = System.currentTimeMillis();
        try {
            List<ComplianceRule> rules = ruleRepo.findByEnabledTrue();
            Set<String> cats = new HashSet<>();
            for (ComplianceRule r : rules) cats.add(r.getCategory());
            boolean ok = cats.containsAll(Arrays.asList(
                    Constants.CAT_CONTINUITY, Constants.CAT_CUMULATIVE, Constants.CAT_RANGE,
                    Constants.CAT_DOOR, Constants.CAT_TRACK, Constants.CAT_SMOOTH));
            c.setPassed(ok);
            c.setMessage("已加载规则 " + rules.size() + " 条，类别 " + cats);
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("规则加载失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkConcurrentIngest() {
        CheckItem c = new CheckItem();
        c.setName("100 设备并发接入");
        c.setCategory("DATA");
        long t0 = System.currentTimeMillis();
        try {
            String taskNo = "SELFCHECK-" + System.currentTimeMillis();
            TransportTask task = new TransportTask();
            task.setTaskNo(taskNo);
            task.setOrigin("上海浦东");
            task.setDestination("德国法兰克福");
            task.setOriginCountry("CN");
            task.setDestCountry("DE");
            task.setStatus(Constants.TASK_IN_TRANSIT);
            task.setDepartureAt(OffsetDateTime.now());
            task.setUpdatedAt(OffsetDateTime.now());
            task = taskRepo.save(task);

            DrugBatch b = new DrugBatch();
            b.setBatchNo("BN-SELF-" + System.currentTimeMillis());
            b.setProductName("测试疫苗");
            b.setDosageForm(Constants.FORM_COLD);
            b.setQuantity(1000);
            batchRepo.save(b);

            List<com.coldchain.compliance.dto.TempSampleDto> samples = new ArrayList<>();
            for (int dev = 0; dev < 100; dev++) {
                String devNo = "SIM-DEVICE-" + dev;
                for (int i = 0; i < 10; i++) {
                    com.coldchain.compliance.dto.TempSampleDto s = new com.coldchain.compliance.dto.TempSampleDto();
                    s.setDeviceNo(devNo);
                    s.setTaskNo(taskNo);
                    s.setSeqNo((long) i);
                    s.setSampleAt(OffsetDateTime.now().plusSeconds(i * 60L));
                    s.setTemperature(BigDecimal.valueOf(5.0 + Math.random()));
                    s.setHumidity(BigDecimal.valueOf(50 + Math.random() * 10));
                    s.setDoorOpen(false);
                    s.setLatitude(BigDecimal.valueOf(31.23 + Math.random() * 0.01));
                    s.setLongitude(BigDecimal.valueOf(121.47 + Math.random() * 0.01));
                    samples.add(s);
                }
            }
            com.coldchain.compliance.dto.IngestRequest req = new com.coldchain.compliance.dto.IngestRequest();
            req.setSamples(samples);
            com.coldchain.compliance.dto.IngestResponse resp = ingestService.ingest(req);
            c.setPassed(resp.getSampleAccepted() >= 1000);
            c.setMessage("accepted=" + resp.getSampleAccepted()
                    + " rejected=" + resp.getSampleRejected()
                    + " elapsedMs=" + resp.getElapsedMs());
            Map<String, Object> extra = new HashMap<>();
            extra.put("taskNo", taskNo);
            c.setExtra(extra);
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("并发接入失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkBlockOnOverheat() {
        CheckItem c = new CheckItem();
        c.setName("含超温的任务触发 BLOCK");
        c.setCategory("AUDIT");
        long t0 = System.currentTimeMillis();
        try {
            String taskNo = "OVERHEAT-" + System.currentTimeMillis();
            TransportTask task = new TransportTask();
            task.setTaskNo(taskNo);
            task.setOrigin("北京");
            task.setDestination("东京");
            task.setStatus(Constants.TASK_IN_TRANSIT);
            task.setDepartureAt(OffsetDateTime.now());
            task.setUpdatedAt(OffsetDateTime.now());
            task = taskRepo.save(task);

            DrugBatch b = new DrugBatch();
            b.setBatchNo("BN-OVERHEAT-" + System.currentTimeMillis());
            b.setProductName("胰岛素");
            b.setDosageForm(Constants.FORM_COLD);
            b.setQuantity(500);
            batchRepo.save(b);

            List<com.coldchain.compliance.dto.TempSampleDto> samples = new ArrayList<>();
            OffsetDateTime t = OffsetDateTime.now();
            double[] temps = {5.0, 5.5, 6.0, 12.0, 12.5, 11.0, 5.0, 6.0, -1.0, 5.0, 6.0, 5.5};
            for (int i = 0; i < temps.length; i++) {
                com.coldchain.compliance.dto.TempSampleDto s = new com.coldchain.compliance.dto.TempSampleDto();
                s.setDeviceNo("OVERHEAT-DEVICE");
                s.setTaskNo(taskNo);
                s.setSeqNo((long) i);
                s.setSampleAt(t.plusMinutes(i * 30L));
                s.setTemperature(BigDecimal.valueOf(temps[i]));
                s.setHumidity(BigDecimal.valueOf(55));
                s.setDoorOpen(false);
                samples.add(s);
            }
            com.coldchain.compliance.dto.IngestRequest req = new com.coldchain.compliance.dto.IngestRequest();
            req.setSamples(samples);
            ingestService.ingest(req);

            com.coldchain.compliance.dto.AuditSummary summary = ruleEngine.audit(task.getId());
            c.setPassed(Constants.AUDIT_BLOCK.equals(summary.getStatus())
                    || summary.getCriticalCount() > 0);
            c.setMessage("status=" + summary.getStatus()
                    + " critical=" + summary.getCriticalCount()
                    + " block=" + summary.getBlockCount()
                    + " total=" + summary.getTotalFindings()
                    + " durationMs=" + summary.getDurationMs());
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("超温审计失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkHashChain() {
        CheckItem c = new CheckItem();
        c.setName("哈希链完整性");
        c.setCategory("IMMUTABLE");
        long t0 = System.currentTimeMillis();
        try {
            List<TempSample> all = sampleRepo.findAll();
            int ok = 0, bad = 0;
            for (TempSample s : all) {
                if (signatureUtil.verify(s.getPayloadHash(), s.getSignature())) ok++;
                else bad++;
            }
            c.setPassed(bad == 0 && ok > 0);
            c.setMessage("verified=" + ok + " failed=" + bad);
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("哈希校验失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkWorm() {
        CheckItem c = new CheckItem();
        c.setName("WORM 触发器生效");
        c.setCategory("IMMUTABLE");
        long t0 = System.currentTimeMillis();
        try {
            boolean blocked = false;
            try {
                em.createNativeQuery(
                        "UPDATE temp_sample SET door_open = NOT door_open WHERE id = (SELECT id FROM temp_sample LIMIT 1)")
                        .executeUpdate();
            } catch (Exception ex) {
                blocked = ex.getMessage() != null && ex.getMessage().toUpperCase().contains("WORM");
            }
            c.setPassed(blocked);
            c.setMessage(blocked ? "WORM 触发器已阻止 UPDATE" : "WORM 未生效（危险）");
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("WORM 校验异常: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkExports() {
        CheckItem c = new CheckItem();
        c.setName("三格式导出");
        c.setCategory("EXPORT");
        long t0 = System.currentTimeMillis();
        try {
            Optional<TransportTask> any = taskRepo.findAll().stream()
                    .filter(t -> !sampleRepo.findByTaskIdOrderBySampleAt(t.getId()).isEmpty())
                    .findFirst();
            if (!any.isPresent()) {
                c.setPassed(false);
                c.setMessage("无数据可导出，请先跑并发接入用例");
                c.setDurationMs(System.currentTimeMillis() - t0);
                return c;
            }
            String taskNo = any.get().getTaskNo();
            String xml = exportService.exportXml(taskNo, "selfcheck");
            String pdf = exportService.exportPdf(taskNo, "selfcheck");
            String xlsx = exportService.exportExcel(taskNo, "selfcheck");
            boolean allOk = Files.exists(Paths.get(xml))
                    && Files.exists(Paths.get(pdf))
                    && Files.exists(Paths.get(xlsx));
            c.setPassed(allOk);
            c.setMessage("XML=" + xml + " | PDF=" + pdf + " | XLSX=" + xlsx);
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("导出失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkCustoms() {
        CheckItem c = new CheckItem();
        c.setName("海关报关单解析与批号匹配");
        c.setCategory("CORE");
        long t0 = System.currentTimeMillis();
        try {
            DrugBatch b = new DrugBatch();
            b.setBatchNo("BN-CUSTOMS-" + System.currentTimeMillis());
            b.setProductName("测试药品");
            b.setDosageForm(Constants.FORM_COLD);
            b.setQuantity(100);
            batchRepo.save(b);

            com.coldchain.compliance.dto.CustomsParseRequest req = new com.coldchain.compliance.dto.CustomsParseRequest();
            req.setDeclNo("DECL-" + System.currentTimeMillis());
            req.setLanguage("zh-CN");
            req.setConsignor("中国出口商");
            req.setConsignee("海外收货人");
            req.setTotalValue("10000");
            req.setCurrency("USD");
            req.setDeclDate(OffsetDateTime.now().toString());
            List<com.coldchain.compliance.dto.CustomsParseRequest.DeclaredBatch> list = new ArrayList<>();
            com.coldchain.compliance.dto.CustomsParseRequest.DeclaredBatch d1 = new com.coldchain.compliance.dto.CustomsParseRequest.DeclaredBatch();
            d1.setBatchNo(b.getBatchNo());
            d1.setQty(100);
            d1.setProduct("测试药品");
            list.add(d1);
            com.coldchain.compliance.dto.CustomsParseRequest.DeclaredBatch d2 = new com.coldchain.compliance.dto.CustomsParseRequest.DeclaredBatch();
            d2.setBatchNo("UNKNOWN-BATCH");
            d2.setQty(50);
            d2.setProduct("未知药品");
            list.add(d2);
            req.setDeclaredBatches(list);

            com.coldchain.compliance.dto.CustomsMatchResult res = customsParseService.parseAndMatch(req);
            c.setPassed(res.getMatched() == 1 && res.getMissing() == 1);
            c.setMessage("total=" + res.getTotal() + " matched=" + res.getMatched()
                    + " missing=" + res.getMissing());
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("海关解析失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }
}
