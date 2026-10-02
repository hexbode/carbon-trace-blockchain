package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 碳排放数据记录表
 */
@Data
@TableName("emission_records")
public class EmissionRecord {

    /** 排放记录编号 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 企业编号 */
    private String enterpriseId;

    /** 企业名称 */
    private String enterpriseName;

    /** 报告期，格式 YYYY-MM */
    private String period;

    /** 排放源编码 */
    private String sourceCode;

    /** 排放源名称 */
    private String sourceName;

    /** 活动数据 */
    private BigDecimal activityData;

    /** 活动数据单位 */
    private String activityUnit;

    /** 本次核算使用的排放因子 */
    private BigDecimal emissionFactor;

    /** 碳排放量 = 活动数据 × 排放因子 */
    private BigDecimal amount;

    /** 排放量单位 */
    private String unit;

    /** 认证状态：certified / pending / rejected */
    private String status;

    /** 电子防伪码 */
    private String fingerprint;

    /** 提交时间 */
    private LocalDateTime createdAt;

    /** 填报人 */
    private String reporter;
}
