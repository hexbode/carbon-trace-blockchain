package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CBAM 监管同步收件箱（消费端幂等）。
 * event_id 唯一，重复投递只入库一次；用于模拟独立监管服务的接收落库。
 */
@Data
@TableName("cbam_regulator_inbox")
public class CbamRegulatorInbox {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件ID，唯一 */
    private String eventId;

    /** 申报包ID */
    private Long declarationId;

    /** 申报包编号 */
    private String declarationNo;

    /** 事件类型 */
    private String eventType;

    /** 事件 JSON */
    private String payload;

    /** 消费时间 */
    private LocalDateTime receivedAt;
}
