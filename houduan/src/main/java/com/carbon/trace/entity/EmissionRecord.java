package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 碳排放数据记录表。
 * 对应表 emission_records，主键 id 为业务生成的排放记录编号（IdType.INPUT）。
 * 业务职责：企业填报的碳排放核算明细（活动数据×排放因子），是认证流程的源头数据；
 * 记录随状态机流转：pending 待认证 → certified 已认证 / rejected 已驳回，认证通过后转入 certification_records 存证。
 * 字段契约：{@code amount} 由活动数据与排放因子计算而来（金额型计算逻辑见 Service 层），
 * 并配套 {@code fingerprint} 防伪码用于后续链上存证核验。
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

    /** 碳排放量 = 活动数据 × 排放因子；量纲与 {@code unit} 一致，业务层负责精度/舍入约定。 */
    private BigDecimal amount;

    /** 排放量单位 */
    private String unit;

    /** 认证状态：certified 已认证 / pending 待认证 / rejected 已驳回（状态机起点为 pending）。 */
    private String status;

    /** 电子防伪码 */
    private String fingerprint;

    /** 提交时间 */
    private LocalDateTime createdAt;

    /** 填报人 */
    private String reporter;
}
