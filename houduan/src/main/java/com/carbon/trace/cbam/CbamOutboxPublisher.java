package com.carbon.trace.cbam;

import com.carbon.trace.entity.CbamOutboxEvent;

/**
 * Outbox 事件投递器抽象。
 * RabbitMQ 实现由 carbon.cbam.outbox.mq-enabled=true 激活；
 * 未启用时中继跳过投递，事件保留为 NEW，便于本地无 Broker 环境启动。
 */
public interface CbamOutboxPublisher {

    /**
     * 投递一条 Outbox 事件。
     *
     * @throws Exception 投递失败，由中继回写 FAILED 并安排重试
     */
    void publish(CbamOutboxEvent event) throws Exception;
}
