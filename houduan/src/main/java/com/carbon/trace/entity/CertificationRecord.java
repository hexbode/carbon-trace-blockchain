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
 * 关联关系：与 emission_records、report_records 共用同一业务编号（三表同号），{@code emissionId} 与 {@code id} 相等；
 * 删除排放记录时本表记录随之清理，但链上存证只标记作废、历史留痕不删除。
 * 字段契约：{@code txHash}/{@code blockIndex} 为 {@code @TableField(exist=false)} 的临时展示字段，业务表不落库，
 * 仅在查询时由链上交易记录回填；{@code status} 支持 valid→revoked 的撤销流转，链上状态为最终裁决依据。
 */
@Data
@TableName("certification_records")
public class CertificationRecord {

    /** 认证编号；与排放记录、报告记录同号（三表同号），作为本表主键。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 关联排放记录编号；与认证编号同号，链上核验与本地记录均按此关联。 */
    private String emissionId;

    /** 企业编号；关联 enterprises.id。 */
    private String enterpriseId;

    /** 企业名称；冗余存储供展示与链上命令使用。 */
    private String enterpriseName;

    /** 报告期，格式 YYYY-MM。 */
    private String period;

    /** 认证排放量，单位 tCO₂；单位与上链编码约定一致（由 ABI/编码层统一缩放，业务侧仅保存数值）。 */
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

    /** 审核人；出具本次认证的审核员姓名。 */
    private String auditor;

    /** 审核意见；来自报告审核时填写的意见。 */
    private String auditOpinion;

    /** 认证时间；审核通过（certified）时写入。 */
    private LocalDateTime certifiedAt;

    /** 状态：valid 有效 / revoked 已撤销；撤销时联动写入撤销原因与撤销时间，链上状态为最终依据。 */
    private String status;

    /** 撤销原因 */
    private String revokeReason;

    /** 撤销时间 */
    private LocalDateTime revokedAt;
}
