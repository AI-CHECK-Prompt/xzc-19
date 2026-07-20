package com.coldchain.compliance.service;

import com.coldchain.compliance.dto.CustomsMatchResult;
import com.coldchain.compliance.dto.CustomsParseRequest;
import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.util.ClockUtil;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 海关报关单结构化解析 + 批号匹配校验。
 * <p>
 * 支持多语言（zh-CN / en-US / ja-JP 等）的字段抽取，
 * 关键字段（批号 / 数量 / 收货人 / 货值 / 日期）抽取后与系统药品批次比对。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomsParseService {

    private final CustomsDeclarationRepository declRepo;
    private final CustomsBatchItemRepository itemRepo;
    private final DrugBatchRepository batchRepo;
    private final OperationLogService opLogService;

    @Transactional
    public CustomsMatchResult parseAndMatch(CustomsParseRequest req) {
        // 1) 落库报关单
        CustomsDeclaration decl = declRepo.findByDeclNo(req.getDeclNo()).orElseGet(CustomsDeclaration::new);
        decl.setDeclNo(req.getDeclNo());
        decl.setTaskId(req.getTaskId());
        decl.setLanguage(req.getLanguage());
        decl.setRawText(req.getRawText());
        decl.setConsignor(req.getConsignor());
        decl.setConsignee(req.getConsignee());
        if (req.getDeclDate() != null && !req.getDeclDate().isEmpty()) {
            try { decl.setDeclDate(OffsetDateTime.parse(req.getDeclDate())); } catch (Exception ignore) {}
        }
        decl.setCurrency(req.getCurrency());
        if (req.getTotalValue() != null) {
            try { decl.setTotalValue(new java.math.BigDecimal(req.getTotalValue())); } catch (Exception ignore) {}
        }
        decl.setParsedJson(JsonUtil.toJson(req));
        decl = declRepo.save(decl);

        // 2) 批号匹配
        List<CustomsMatchResult.BatchItem> results = new ArrayList<>();
        int matched = 0, mismatched = 0, missing = 0;

        // 清理旧 items（如有重传）
        itemRepo.findByDeclarationId(decl.getId()).forEach(itemRepo::delete);

        if (req.getDeclaredBatches() != null) {
            for (CustomsParseRequest.DeclaredBatch db : req.getDeclaredBatches()) {
                CustomsBatchItem item = new CustomsBatchItem();
                item.setDeclarationId(decl.getId());
                item.setDeclaredBatchNo(db.getBatchNo());
                item.setDeclaredQty(db.getQty());
                item.setDeclaredProduct(db.getProduct());

                Optional<DrugBatch> mb = batchRepo.findByBatchNo(db.getBatchNo());
                if (mb.isPresent()) {
                    DrugBatch b = mb.get();
                    item.setMatchedBatchId(b.getId());
                    boolean productMatch = db.getProduct() == null
                            || db.getProduct().isEmpty()
                            || (b.getProductName() != null
                                && (b.getProductName().contains(db.getProduct())
                                    || db.getProduct().contains(b.getProductName())));
                    boolean qtyMatch = db.getQty() == null
                            || b.getQuantity() == null
                            || b.getQuantity().equals(db.getQty());
                    if (productMatch && qtyMatch) {
                        item.setMatchStatus("MATCHED");
                        matched++;
                    } else {
                        item.setMatchStatus("MISMATCH");
                        item.setMatchDetail("产品名/数量与系统批次不一致");
                        mismatched++;
                    }
                } else {
                    item.setMatchStatus("MISSING");
                    item.setMatchDetail("系统未找到该批号");
                    missing++;
                }
                itemRepo.save(item);

                CustomsMatchResult.BatchItem vo = new CustomsMatchResult.BatchItem();
                vo.setDeclaredBatchNo(db.getBatchNo());
                vo.setMatchedBatchNo(mb.map(DrugBatch::getBatchNo).orElse(null));
                vo.setDeclaredProduct(db.getProduct());
                vo.setDeclaredQty(db.getQty());
                vo.setActualQty(mb.map(DrugBatch::getQuantity).orElse(null));
                vo.setMatchStatus(item.getMatchStatus());
                vo.setDetail(item.getMatchDetail());
                results.add(vo);
            }
        }

        CustomsMatchResult out = new CustomsMatchResult();
        out.setDeclarationId(decl.getId());
        out.setDeclNo(decl.getDeclNo());
        out.setTaskId(decl.getTaskId());
        out.setTotal(results.size());
        out.setMatched(matched);
        out.setMismatched(mismatched);
        out.setMissing(missing);
        out.setOverallOk(mismatched == 0 && missing == 0 && matched > 0);
        out.setItems(results);

        opLogService.logAsync("CUSTOMS_USER", "CUSTOMS", "PARSE_DECLARATION", "DECLARATION",
                decl.getDeclNo(), JsonUtil.toJson(out), out.isOverallOk() ? "OK" : "FAIL",
                "127.0.0.1", "customs-service");

        log.info("【报关-解析】decl={} total={} matched={} mismatched={} missing={}",
                decl.getDeclNo(), out.getTotal(), matched, mismatched, missing);
        return out;
    }
}
