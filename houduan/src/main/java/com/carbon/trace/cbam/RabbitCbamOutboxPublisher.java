package com.carbon.trace.cbam;

import com.carbon.trace.entity.CbamOutboxEvent;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 投递实现。
 * 消息属性携带 messageId=eventId，供消费端幂等去重；
 * 同时把事件类型与申报包信息放入 header，便于消费端不解析 JSON 也能落库。
 */
@Component
@ConditionalOnProperty(name = "carbon.cbam.outbox.mq-enabled", havingValue = "true")
public class RabbitCbamOutboxPublisher implements CbamOutboxPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;

    public RabbitCbamOutboxPublisher(RabbitTemplate rabbitTemplate,
                                     @Value("${carbon.cbam.outbox.exchange:cbam.events}") String exchange,
                                     @Value("${carbon.cbam.outbox.routing-key:cbam.declaration}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    @Override
    public void publish(CbamOutboxEvent event) {
        MessagePostProcessor processor = message -> {
            message.getMessageProperties().setMessageId(event.getEventId());
            message.getMessageProperties().setContentType("application/json");
            message.getMessageProperties().setHeader("eventType", event.getEventType());
            message.getMessageProperties().setHeader("declarationId", String.valueOf(event.getAggregateId()));
            return message;
        };
        rabbitTemplate.convertAndSend(exchange, routingKey, event.getPayload(), processor);
    }
}
