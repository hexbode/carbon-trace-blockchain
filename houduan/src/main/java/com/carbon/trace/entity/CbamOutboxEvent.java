package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CBAM 事务性 Outbox 事件。
 * 与申报包状态变更在同一数据库事务内写入，后台中继再投递到 RabbitMQ，
 * 避免“业务已提交但消息未发出”的双写不一致。
 */
@Data
@TableName("cbam_outbox")
public class CbamOutboxEvent {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件ID（UUID），消费端幂等键 */
    private String eventId;

    /** 聚合类型，默认 CBAM_DECLARATION */
    private String aggregateType;

    /** 聚合ID（申报包ID） */
    private Long aggregateId;

    /** 事件类型：DECLARATION_GENERATED/SUBMITTED/APPROVED/RETURNED */
    private String eventType;

    /** 事件 JSON */
    private String payload;

    /** 状态：NEW/SENT/FAILED */
    private String status;

    /** 重试次数 */
    private Integer retryCount;

    /** 下次重试时间 */
    private LocalDateTime nextRetryAt;

    /** 最近一次错误 */
    private String lastError;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 投递成功时间 */
    private LocalDateTime sentAt;
}
