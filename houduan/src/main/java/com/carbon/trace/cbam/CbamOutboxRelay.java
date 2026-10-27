package com.carbon.trace.cbam;

import com.carbon.trace.entity.CbamOutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbox 中继：轮询待投递事件并发布到 MQ，失败按退避重试。
 *
 * 默认每 5 秒执行一次（carbon.cbam.outbox.relay-interval-ms 可调）；
 * 未启用 MQ 时直接跳过，事件保留 NEW，不产生连接错误。
 */
@Component
public class CbamOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(CbamOutboxRelay.class);

    private final CbamOutboxService outboxService;
    private final ObjectProvider<CbamOutboxPublisher> publisherProvider;

    public CbamOutboxRelay(CbamOutboxService outboxService,
                           ObjectProvider<CbamOutboxPublisher> publisherProvider) {
        this.outboxService = outboxService;
        this.publisherProvider = publisherProvider;
    }

    @Scheduled(fixedDelayString = "${carbon.cbam.outbox.relay-interval-ms:5000}")
    public void relay() {
        try {
            relayBatch();
        } catch (Exception e) {
            log.error("CBAM Outbox 中继异常", e);
        }
    }

    /** 手动触发一批中继，供监管端/运维排障使用。 */
    public Map<String, Object> relayNow() {
        return relayBatch();
    }

    private Map<String, Object> relayBatch() {
        CbamOutboxPublisher publisher = publisherProvider.getIfAvailable();
        Map<String, Object> result = new LinkedHashMap<>();
        if (publisher == null) {
            result.put("mqEnabled", false);
            result.put("sent", 0);
            result.put("failed", 0);
            result.put("skipped", true);
            return result;
        }
        List<CbamOutboxEvent> pending = outboxService.pending(50);
        int sent = 0;
        int failed = 0;
        for (CbamOutboxEvent event : pending) {
            try {
                publisher.publish(event);
                outboxService.markSent(event);
                sent++;
            } catch (Exception e) {
                outboxService.markFailed(event, e.getMessage());
                failed++;
                log.warn("CBAM Outbox 投递失败 eventId={} type={}", event.getEventId(), event.getEventType(), e);
            }
        }
        result.put("mqEnabled", true);
        result.put("sent", sent);
        result.put("failed", failed);
        result.put("skipped", false);
        if (sent + failed > 0) {
            log.info("CBAM Outbox 中继完成: sent={}, failed={}", sent, failed);
        }
        return result;
    }
}
