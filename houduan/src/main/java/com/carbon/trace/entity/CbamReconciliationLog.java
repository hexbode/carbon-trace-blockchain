package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CBAM 申报包对账日志。
 * 对应表 cbam_reconciliation_logs；定时任务或监管手动对账时写入，
 * 记录快照版本/实时版本、快照合计/实时合计与一致性结论。
 */
@Data
@TableName("cbam_reconciliation_logs")
public class CbamReconciliationLog {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申报包ID */
    private Long declarationId;

    /** 申报包编号 */
    private String declarationNo;

    /** 企业编号 */
    private String enterpriseId;

    /** 年度 */
    private Integer reportYear;

    /** 申报包生成时的数据版本 */
    private Long snapshotVersion;

    /** 当前数据版本 */
    private Long liveVersion;

    /** 快照隐含排放合计 tCO2e */
    private BigDecimal snapshotTotal;

    /** 实时隐含排放合计 tCO2e */
    private BigDecimal liveTotal;

    /** 结论：consistent / stale */
    private String status;

    /** 差异说明 */
    private String detail;

    /** 对账时间 */
    private LocalDateTime checkedAt;
}
