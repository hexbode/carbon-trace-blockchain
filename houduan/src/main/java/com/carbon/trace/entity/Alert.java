package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异常告警表
 */
@Data
@TableName("alerts")
public class Alert {

    /** 告警编号 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 企业编号 */
    private String enterpriseId;

    /** 企业名称 */
    private String enterpriseName;

    /** 告警类型：data_anomaly/factor_mismatch/late_report */
    @TableField("type")
    private String type;

    /** 类型中文名 */
    private String typeLabel;

    /** 级别：high 高危 / medium 中危 / low 低危 */
    @TableField("level")
    private String level;

    /** 告警内容 */
    private String message;

    /** 涉及报告期 */
    private String period;

    /** 状态：pending 待处理 / resolved 已处理 */
    private String status;

    /** 触发时间 */
    private LocalDateTime createdAt;
}
