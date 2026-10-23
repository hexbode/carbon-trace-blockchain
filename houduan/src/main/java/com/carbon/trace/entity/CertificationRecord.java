package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 认证记录表。
 * 对应表 certification_records，主键 id 为业务生成的认证编号（IdType.INPUT）。
 * 业务职责：审核机构对企业排放记录出具认证后的权威存证凭证，是追溯链上证书（ChainCertificate）的落库镜像；
 * 认证通过后写入本表，并将关键字段同步上链，追溯码/区块信息通过链上数据回填展示。
 * 字段契约：{@code txHash}/{@code blockIndex} 为 {@code @TableField(exist=false)} 的临时展示字段，业务表不落库，
 * 仅在查询时由链上交易记录回填；{@code status} 支持 valid→revoked 的撤销流转。
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

    /** 认证排放量；单位与上链编码约定一致（由 ABI/编码层统一缩放，业务侧仅保存数值）。 */
    private BigDecimal amount;

    /** 电子防伪码；由业务关键字段摘要生成的指纹，落库并写入链上，用于防篡改与对外核验。 */
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

    /** 状态：valid 有效 / revoked 已撤销；撤销时联动写入撤销原因与撤销时间。 */
    private String status;

    /** 撤销原因 */
    private String revokeReason;

    /** 撤销时间 */
    private LocalDateTime revokedAt;
}
