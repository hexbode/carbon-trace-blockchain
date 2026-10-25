package com.carbon.trace.chain.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 合规判定入参。
 *
 * 输入边界说明：链上无法自行聚合业务库数据，因此「本期/上期合计、年累计、活动数据、因子」
 * 由后端从数据库聚合后传入，合约只负责按规则判定。
 *
 * 字段口径：
 *  - 金额字段为十进制原值，网关按 ×100 定标后上链；
 *  - 日期字段使用整数 YYYYMM（不是 YYYY-MM），非法月份由合约的迟报规则自行处理；
 *  - {@code previousTotal} 无上期时传 0（不是 null），否则环比规则的分母会退化为 0 而被跳过。
 */
@Data
public class ComplianceInput {

    /** 企业编号（链上按企业维度维护配额、年累计与因子） */
    private String enterpriseId;

    /** 报告期所在年份 */
    private int year;

    /** 报告期 YYYY-MM */
    private String period;

    /** 排放源编码 */
    private String sourceCode;

    /** 本期（该报告期）排放量合计 */
    private BigDecimal currentTotal;

    /** 上一报告期排放量合计（无则传 0） */
    private BigDecimal previousTotal;

    /** 本年度累计排放量 */
    private BigDecimal yearToDate;

    /** 本次活动数据 */
    private BigDecimal activityData;

    /** 本次核算使用的排放因子 */
    private BigDecimal factor;

    /** 报告期（整数 YYYYMM，如 202401） */
    private long periodYyyymm;

    /** 当前月份（整数 YYYYMM，由后端按系统时间传入） */
    private long currentYyyymm;
}
