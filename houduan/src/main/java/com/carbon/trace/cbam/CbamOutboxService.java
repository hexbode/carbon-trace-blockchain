package com.carbon.trace.cbam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.entity.CbamDeclaration;
import com.carbon.trace.entity.CbamOutboxEvent;
import com.carbon.trace.mapper.CbamOutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CBAM Outbox 服务：事件写入、待投递查询、投递结果回写。
 *
 * append 由申报包状态变更事务调用，与业务变更同库同事务；
 * 中继（Relay）在事务提交后异步投递，失败按指数退避重试。
 */
@Service
public class CbamOutboxService {

    private static final int MAX_RETRY_DELAY_SECONDS = 300;

    private final CbamOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    public CbamOutboxService(CbamOutboxMapper outboxMapper, ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
    }

    /** 在同一事务内追加事件。 */
    public void append(CbamDeclaration declaration, String eventType) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("occurredAt", now.toString());
        payload.put("declarationId", declaration.getId());
        payload.put("declarationNo", declaration.getDeclarationNo());
        payload.put("enterpriseId", declaration.getEnterpriseId());
        payload.put("reportYear", declaration.getReportYear());
        payload.put("status", declaration.getStatus());
        payload.put("dataVersion", declaration.getDataVersion());
        payload.put("completeness", declaration.getCompleteness());
        payload.put("totalEmissions", declaration.getTotalEmissions());
        payload.put("totalQuantity", declaration.getTotalQuantity());

        CbamOutboxEvent event = new CbamOutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType("CBAM_DECLARATION");
        event.setAggregateId(declaration.getId());
        event.setEventType(eventType);
        event.setPayload(toJson(payload));
        event.setStatus("NEW");
        event.setRetryCount(0);
        event.setCreatedAt(now);
        outboxMapper.insert(event);
    }

    /** 查询待投递事件：NEW，或已到重试时间的 FAILED。 */
    public List<CbamOutboxEvent> pending(int limit) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<CbamOutboxEvent> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(CbamOutboxEvent::getStatus, "NEW")
                        .or()
                        .eq(CbamOutboxEvent::getStatus, "FAILED"))
                .and(w -> w.isNull(CbamOutboxEvent::getNextRetryAt)
                        .or()
                        .le(CbamOutboxEvent::getNextRetryAt, now))
                .orderByAsc(CbamOutboxEvent::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        return outboxMapper.selectList(wrapper);
    }

    /** 投递成功。 */
    public void markSent(CbamOutboxEvent event) {
        event.setStatus("SENT");
        event.setSentAt(LocalDateTime.now());
        event.setLastError("");
        outboxMapper.updateById(event);
    }

    /** 投递失败：重试次数 +1，按指数退避安排下次重试。 */
    public void markFailed(CbamOutboxEvent event, String error) {
        int retry = event.getRetryCount() == null ? 0 : event.getRetryCount();
        retry++;
        int delay = Math.min(MAX_RETRY_DELAY_SECONDS, 30 * (1 << Math.min(retry - 1, 3)));
        event.setStatus("FAILED");
        event.setRetryCount(retry);
        event.setNextRetryAt(LocalDateTime.now().plusSeconds(delay));
        event.setLastError(truncate(error, 500));
        outboxMapper.updateById(event);
    }

    /** 运维查询：按状态筛选事件。 */
    public List<CbamOutboxEvent> listEvents(String status, Integer limit) {
        LambdaQueryWrapper<CbamOutboxEvent> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(CbamOutboxEvent::getStatus, status);
        }
        wrapper.orderByDesc(CbamOutboxEvent::getId)
                .last("LIMIT " + (limit == null ? 50 : Math.max(1, Math.min(limit, 200))));
        return outboxMapper.selectList(wrapper);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Outbox 事件序列化失败");
        }
    }

    private String truncate(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
