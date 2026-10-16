package com.carbon.trace.chain.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 合规判定结果（由智能合约 CarbonCompliance.checkAll 返回）。
 *
 * flags 位掩码：1 配额超限 / 2 环比超阈值 / 4 活动数据为 0 / 8 因子不符 / 16 迟报
 */
@Data
public class ComplianceResult {

    public static final int FLAG_QUOTA = 1;
    public static final int FLAG_FLUCTUATION = 2;
    public static final int FLAG_ACTIVITY = 4;
    public static final int FLAG_FACTOR = 8;
    public static final int FLAG_LATE = 16;

    /** 判定未产生（链不可用且未回退），flags 置为该哨兵值 */
    public static final int FLAGS_UNKNOWN = -1;

    /** 命中的规则位掩码 */
    private int flags;

    /** 环比基点（1bp = 0.01%），阈值内为 ±阈值范围内数值 */
    private long deltaBp;

    /** 链上记录的最近一次排放因子（因子不符时用于展示对比） */
    private BigDecimal previousFactor;

    /** 链上年度配额（0 表示未配置） */
    private BigDecimal quota;

    /** 链上/传入的年度已用排放量 */
    private BigDecimal yearToDate;

    /** 合约当前配置的环比波动阈值（基点，1bp = 0.01%） */
    private long thresholdBp;

    /** 判定是否由合约执行（false 表示回退到本地 Java 规则） */
    private boolean onChain;

    /** 回退/异常说明（可空） */
    private String notice;

    public boolean isCompliant() {
        return flags == 0;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }
}
