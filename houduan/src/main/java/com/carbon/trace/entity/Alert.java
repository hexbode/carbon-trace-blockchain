package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异常告警表。
 * 对应表 alerts，主键 id 为业务生成的告警编号（IdType.INPUT）。
 * 业务职责：记录碳排放数据的合规异常（数据异常、因子不匹配、迟报等），由合规校验流程自动触发写入；
 * 另记录链上同步失败告警（type=chain_failed），由企业端/监管端人工处理流转，状态 pending→resolved。
 * 关联关系：{@code enterpriseId} 关联 enterprises.id，{@code period} 对应排放记录/报告记录的报告期；
 * 同一企业、同一报告期、同一类型且同一级别的告警只保留一条（写入时去重）。
 * 字段契约：{@code type} 与 {@code level} 为数据库保留字，通过 {@code @TableField} 显式映射，取值为约定的英文枚举。
 */
@Data
@TableName("alerts")
public class Alert {

    /** 告警编号；格式 ALT + 3 位序号（超过 999 自动加宽），由应用内取号生成。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 企业编号；关联 enterprises.id，链上同步失败告警缺失时无法落库。 */
    private String enterpriseId;

    /** 企业名称；冗余存储便于列表展示。 */
    private String enterpriseName;

    /** 告警类型：data_anomaly 数据异常 / factor_mismatch 因子不匹配 / late_report 迟报 / chain_failed 链上同步失败。 */
    @TableField("type")
    private String type;

    /** 类型中文名；与 type 对应的展示文案（如“数据异常”）。 */
    private String typeLabel;

    /** 级别：high 高危 / medium 中危 / low 低危；决定展示排序与处理优先级。 */
    @TableField("level")
    private String level;

    /** 告警内容；中文文案，包含涉及数值、阈值与处理建议。 */
    private String message;

    /** 涉及报告期；格式 YYYY-MM，与排放记录/报告记录一致。 */
    private String period;

    /** 状态：pending 待处理 / resolved 已处理；仅由告警处理流转。 */
    private String status;

    /** 触发时间；规则判定或链上同步失败时写入。 */
    private LocalDateTime createdAt;
}
