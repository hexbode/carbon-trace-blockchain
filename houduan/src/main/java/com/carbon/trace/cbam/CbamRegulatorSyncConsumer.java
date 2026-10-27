package com.carbon.trace.cbam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.entity.CbamRegulatorInbox;
import com.carbon.trace.mapper.CbamRegulatorInboxMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 监管同步事件消费者（模拟独立监管服务接收）。
 *
 * 幂等策略：
 *  1. 先按 messageId（=outbox.event_id）查收件箱，已存在直接忽略；
 *  2. 并发窗口由 cbam_regulator_inbox.event_id 唯一索引兜底，重复插入捕获后忽略。
 */
@Component
@ConditionalOnProperty(name = "carbon.cbam.outbox.mq-enabled", havingValue = "true")
public class CbamRegulatorSyncConsumer {

    private final CbamRegulatorInboxMapper inboxMapper;
    private final ObjectMapper objectMapper;

    public CbamRegulatorSyncConsumer(CbamRegulatorInboxMapper inboxMapper, ObjectMapper objectMapper) {
        this.inboxMapper = inboxMapper;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "${carbon.cbam.outbox.queue:cbam.regulator.sync}")
    public void onMessage(Message message) {
        String eventId = message.getMessageProperties().getMessageId();
        if (!StringUtils.hasText(eventId)) {
            return;
        }
        Long existing = inboxMapper.selectCount(new LambdaQueryWrapper<CbamRegulatorInbox>()
                .eq(CbamRegulatorInbox::getEventId, eventId));
        if (existing != null && existing > 0) {
            return;
        }
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        CbamRegulatorInbox inbox = new CbamRegulatorInbox();
        inbox.setEventId(eventId);
        inbox.setEventType(header(message, "eventType"));
        inbox.setDeclarationId(parseLong(header(message, "declarationId")));
        inbox.setDeclarationNo(parseDeclarationNo(payload));
        inbox.setPayload(payload);
        inbox.setReceivedAt(LocalDateTime.now());
        try {
            inboxMapper.insert(inbox);
        } catch (DuplicateKeyException e) {
            // 幂等：并发/重复投递，唯一索引兜底后直接忽略
        }
    }

    private String header(Message message, String name) {
        Object value = message.getMessageProperties().getHeaders().get(name);
        return value == null ? null : String.valueOf(value);
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String parseDeclarationNo(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode no = node.get("declarationNo");
            return no == null ? null : no.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
