package com.coldchain.compliance.service;

import com.coldchain.compliance.entity.OperationLog;
import com.coldchain.compliance.repository.OperationLogRepository;
import com.coldchain.compliance.security.SignatureUtil;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/**
 * 操作日志服务（GxP 全留痕）。
 * <p>
 * 每条日志计算 payload_hash + prev_hash 形成哈希链，并签名。
 * 配合 DB 触发器实现物理级不可篡改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final OperationLogRepository repo;
    private final SignatureUtil signatureUtil;

    @Async
    @Transactional
    public void logAsync(String userId, String role, String action,
                         String resourceType, String resourceId,
                         String payload, String result,
                         String ip, String userAgent) {
        try {
            log(userId, role, action, resourceType, resourceId, payload, result, ip, userAgent);
        } catch (Exception e) {
            log.error("【操作日志】异步写入失败: action={} err={}", action, e.getMessage());
        }
    }

    @Transactional
    public OperationLog log(String userId, String role, String action,
                            String resourceType, String resourceId,
                            String payload, String result,
                            String ip, String userAgent) {
        Optional<OperationLog> last = repo.findAll().stream()
                .max((a, b) -> Long.compare(a.getId() == null ? 0 : a.getId(),
                                           b.getId() == null ? 0 : b.getId()));
        String prev = last.map(OperationLog::getPayloadHash).orElse(null);

        String body = payload == null ? "" : payload;
        String hash = signatureUtil.chainHash(body, prev);
        String sig = signatureUtil.sign(hash);

        OperationLog op = new OperationLog();
        op.setUserId(userId);
        op.setUserRole(role);
        op.setAction(action);
        op.setResourceType(resourceType);
        op.setResourceId(resourceId);
        op.setIp(ip);
        op.setUserAgent(userAgent);
        op.setPayload(body);
        op.setResult(result);
        op.setPayloadHash(hash);
        op.setPrevHash(prev);
        op.setSignature(sig);
        return repo.save(op);
    }
}
