package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
 * 字段契约：{@code amount} 为报告期间的排放量，{@code fingerprint} 为报告防伪码。
 */
@Data
@TableName("report_records")
public class ReportRecord {

    /** 报告编号 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 关联排放记录编号 */
    private String emissionId;

    /** 企业编号 */
    private String enterpriseId;

    /** 企业名称 */
    private String enterpriseName;

    /** 报告期 YYYY-MM */
    private String period;

    /** 排放源名称 */
    private String sourceName;

    /** 排放量 */
    private BigDecimal amount;

    /** 审核状态：certified/pending/rejected */
    private String status;

    /** 电子防伪码 */
    private String fingerprint;

    /** 提交人 */
    private String submitter;

    /** 提交时间 */
    private LocalDateTime submittedAt;

    /** 审核人 */
    private String auditor;

    /** 审核时间 */
    private LocalDateTime auditedAt;

    /** 审核意见 */
    private String auditOpinion;
}
