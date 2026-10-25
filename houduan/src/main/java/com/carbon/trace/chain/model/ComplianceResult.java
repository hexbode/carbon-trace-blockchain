package com.carbon.trace.chain.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 合规判定结果（由智能合约 CarbonCompliance.checkAll 返回）。
 *
 * flags 位掩码：1 配额超限 / 2 环比超阈值 / 4 活动数据为 0 / 8 因子不符 / 16 迟报
 *
 * 位掩码可同时命中多条规则，调用方必须按位判断，不能与常量做等值比较
 * （等值比较会漏掉「同时超配额且迟报」这类组合）。{@code FLAGS_UNKNOWN=-1}
 * 表示判定未产生，此时不应把 -1 当作「命中全部规则」。
 */
@Data
public class ComplianceResult {

    /** 配额超限：年度累计排放量已超过该企业该年度配额 */
    public static final int FLAG_QUOTA = 1;
    /** 环比超阈值：与上一报告期相比的波动绝对值超过链上配置的基点阈值 */
    public static final int FLAG_FLUCTUATION = 2;
    /** 活动数据为 0：填报的活动数据缺失或为零，判定为无效数据 */
    public static final int FLAG_ACTIVITY = 4;
    /** 因子不符：本次排放因子与该企业该排放源链上记录的上次因子不一致 */
    public static final int FLAG_FACTOR = 8;
    /** 迟报：报告期早于「当前月份 - 迟报判定月数」 */
    public static final int FLAG_LATE = 16;

    /** 判定未产生（链不可用且未回退），flags 置为该哨兵值 */
    public static final int FLAGS_UNKNOWN = -1;

    /** 命中的规则位掩码 */
    private int flags;

    /** 环比基点（1bp = 0.01%），阈值内为 ±阈值范围内数值；无上期数据时保持默认 0 */
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

    /**
     * 是否全部规则通过。
     *
     * 仅 flags 恰为 0 时通过；{@code FLAGS_UNKNOWN=-1} 会被判为不通过，
     * 调用方若需要区分「未判定」与「不合规」，应另外判断 {@link #getFlags()}。
     *
     * @return true 表示没有任何规则命中
     */
    public boolean isCompliant() {
        return flags == 0;
    }

    /**
     * 判断是否命中指定规则。
     *
     * @param flag 规则位，取 {@code FLAG_*} 之一；传入组合位时表示「任一命中」
     * @return true 表示该位已置 1
     */
    public boolean has(int flag) {
        return (flags & flag) != 0;
    }
}
