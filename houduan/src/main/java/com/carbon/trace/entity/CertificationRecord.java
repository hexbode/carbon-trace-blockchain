package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 认证记录表（上链存证的认证凭证）
 */
@Data
@TableName("certification_records")
public class CertificationRecord {

    /** 认证编号 */
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

    /** 认证排放量 */
    private BigDecimal amount;

    /** 电子防伪码 */
    private String fingerprint;

    /**
     * 区块链交易哈希：不在业务表落库，仅由链上交易表回填用于展示
     */
    @TableField(exist = false)
    private String txHash;

    /**
     * 所在区块高度：不在业务表落库，仅由链上交易表回填用于展示
     */
    @TableField(exist = false)
    private Integer blockIndex;

    /** 审核人 */
    private String auditor;

    /** 审核意见 */
    private String auditOpinion;

    /** 认证时间 */
    private LocalDateTime certifiedAt;

    /** 状态：valid 有效 / revoked 已撤销 */
    private String status;

    /** 撤销原因 */
    private String revokeReason;

    /** 撤销时间 */
    private LocalDateTime revokedAt;
}
