package com.carbon.trace.cbam;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CBAM RabbitMQ 拓扑（mq-enabled=true 时生效）：
 *
 *   cbam.events (topic) --routing cbam.declaration--> cbam.regulator.sync
 *                                                           |
 *                                                     消费失败 / 拒绝
 *                                                           v
 *   cbam.dlx (direct) --cbam.dead--> cbam.regulator.sync.dlq
 *
 * 主队列配置死信交换机，监听器 default-requeue-rejected=false，
 * 毒消息不会无限重回队列。
 */
@Configuration
@EnableRabbit
@ConditionalOnProperty(name = "carbon.cbam.outbox.mq-enabled", havingValue = "true")
public class CbamRabbitConfig {

    @Bean
    public TopicExchange cbamEventExchange(
            @Value("${carbon.cbam.outbox.exchange:cbam.events}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public DirectExchange cbamDeadLetterExchange(
            @Value("${carbon.cbam.outbox.dead-letter-exchange:cbam.dlx}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    public Queue cbamSyncQueue(
            @Value("${carbon.cbam.outbox.queue:cbam.regulator.sync}") String queue,
            @Value("${carbon.cbam.outbox.dead-letter-exchange:cbam.dlx}") String deadLetterExchange) {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey("cbam.dead")
                .build();
    }

    @Bean
    public Queue cbamDeadLetterQueue(
            @Value("${carbon.cbam.outbox.dead-letter-queue:cbam.regulator.sync.dlq}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    public Binding cbamSyncBinding(Queue cbamSyncQueue, TopicExchange cbamEventExchange,
                                   @Value("${carbon.cbam.outbox.routing-key:cbam.declaration}") String routingKey) {
        return BindingBuilder.bind(cbamSyncQueue).to(cbamEventExchange).with(routingKey);
    }

    @Bean
    public Binding cbamDeadLetterBinding(Queue cbamDeadLetterQueue, DirectExchange cbamDeadLetterExchange) {
        return BindingBuilder.bind(cbamDeadLetterQueue).to(cbamDeadLetterExchange).with("cbam.dead");
    }
}
