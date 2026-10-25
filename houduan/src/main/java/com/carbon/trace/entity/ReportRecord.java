package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报告提交记录表。
 * 对应表 report_records，主键 id 为业务生成的报告编号（IdType.INPUT）。
 * 业务职责：记录企业对外提交排放报告的报送行为及审核结果，是迟报告警（late_report）与报送进度统计的依据；
 * 报告审核状态机与排放记录一致（pending → certified/rejected），审核人、审核时间、审核意见在流转时回填。
 * 关联关系：与 emission_records、certification_records 共用同一业务编号（三表同号），
 * {@code emissionId} 正常情况下与 {@code id} 相等；删除排放记录时本表记录随之清理。
 * 报告记录只保存排放量，活动数据与排放因子在查询时按 emissionId 联查排放记录补全。
 * 字段契约：{@code amount} 为报告期间的排放量，{@code fingerprint} 为报告防伪码。
 */
@Data
@TableName("report_records")
public class ReportRecord {

    /** 报告编号；与排放记录、认证记录同号（CR + 6 位序号），同时作为本表主键。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 关联排放记录编号；与报告编号同号，用于联查 emission_records 补全活动数据、排放因子与单位。 */
    private String emissionId;

    /** 企业编号 */
    private String enterpriseId;

    /** 企业名称 */
    private String enterpriseName;

    /** 报告期 YYYY-MM */
    private String period;

    /** 排放源名称 */
    private String sourceName;

    /** 报告期排放量，单位 tCO₂；与关联排放记录的 amount 一致（活动数据 × 排放因子，保留 2 位小数）。 */
    private BigDecimal amount;

    /** 审核状态：pending 待审核 / certified 已认证 / rejected 已驳回；由报告审核流转写回，并与排放记录状态同步。 */
    private String status;

    /** 电子防伪码；与排放记录 fingerprint 一致，认证通过后作为链上存证核验依据。 */
    private String fingerprint;

    /** 提交人；新增填报时取当前登录用户姓名。 */
    private String submitter;

    /** 提交时间；与排放记录创建时间一致。 */
    private LocalDateTime submittedAt;

    /** 审核人；审核时回填为当前登录审核员姓名。 */
    private String auditor;

    /** 审核时间；审核时回填。 */
    private LocalDateTime auditedAt;

    /** 审核意见；审核时由审核员填写，驳回时用于说明原因。 */
    private String auditOpinion;

    /*
     * 以下字段来自关联的排放记录（emission_records），仅用于接口展示，不落库。
     * 报告记录本身只保存排放量，活动数据与排放因子需要通过 emissionId 联查补全。
     */
    /** 活动数据；仅展示字段（@TableField(exist=false)），由关联排放记录联查回填。 */
    @TableField(exist = false)
    private BigDecimal activityData;

    /** 活动数据单位；仅展示字段（@TableField(exist=false)），如 t煤、万kWh。 */
    @TableField(exist = false)
    private String activityUnit;

    /** 本次核算使用的排放因子；仅展示字段（@TableField(exist=false)），由关联排放记录联查回填。 */
    @TableField(exist = false)
    private BigDecimal emissionFactor;

    /** 排放量单位；仅展示字段（@TableField(exist=false)），通常为 tCO₂。 */
    @TableField(exist = false)
    private String unit;
}
