package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CBAM 核查报告（装置级、年度）。
 * 对应表 cbam_verification_reports；实际排放只有在存在结论为 verified 的报告时
 * 才会被判定为“核查完备”。
 */
@Data
@TableName("cbam_verification_reports")
public class CbamVerificationReport {

    /** 报告主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 企业编号 */
    private String enterpriseId;

    /** 装置ID，关联 cbam_installations.id */
    private Integer installationId;

    /** 核查年度 */
    private Integer reportYear;

    /** 核查机构ID，关联 cbam_verifiers.id */
    private Integer verifierId;

    /** 核查报告编号 */
    private String reportNo;

    /** 结论：verified 通过 / not_verified 未通过 */
    private String conclusion;

    /** 出具日期 */
    private LocalDate issuedAt;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
