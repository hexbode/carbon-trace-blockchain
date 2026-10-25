package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 碳排放数据记录表。
 * 对应表 emission_records，主键 id 为业务生成的排放记录编号（IdType.INPUT）。
 * 业务职责：企业填报的碳排放核算明细（活动数据×排放因子），是认证流程的源头数据；
 * 记录随状态机流转：pending 待认证 → certified 已认证 / rejected 已驳回，认证通过后转入 certification_records 存证。
 * 关联关系：新增时同号派生 report_records 报告记录，认证通过后再同号生成 certification_records（三表同号）；
 * 删除时按「认证 → 报告 → 排放」顺序清理本地记录，链上存证只标记作废。
 * 字段契约：{@code amount} 由活动数据与排放因子计算而来（金额型计算逻辑见 Service 层），
 * 并配套 {@code fingerprint} 防伪码用于后续链上存证核验。
 */
@Data
@TableName("emission_records")
public class EmissionRecord {

    /** 排放记录编号；格式 CR + 6 位序号，与报告记录、认证记录同号（三表同号），且不可与链上已有编号重复。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 企业编号；关联 enterprises.id，企业端填报时强制为本企业。 */
    private String enterpriseId;

    /** 企业名称；冗余存储便于展示，缺失时由企业档案补全。 */
    private String enterpriseName;

    /** 报告期，格式 YYYY-MM。 */
    private String period;

    /** 排放源编码；关联 emission_sources.code，决定本次核算使用的默认排放因子。 */
    private String sourceCode;

    /** 排放源名称；取自排放源字典，便于展示与关键字检索。 */
    private String sourceName;

    /** 活动数据；企业填报的燃料消耗量、用电量等原始活动水平。 */
    private BigDecimal activityData;

    /** 活动数据单位；由排放因子单位中斜杠后的部分解析而来，如 t煤、万kWh。 */
    private String activityUnit;

    /** 本次核算使用的排放因子；取自排放源字典为默认值，用于计算排放量。 */
    private BigDecimal emissionFactor;

    /** 碳排放量 = 活动数据 × 排放因子；量纲与 {@code unit} 一致，业务层负责精度/舍入约定。 */
    private BigDecimal amount;

    /** 排放量单位；固定为 tCO₂。 */
    private String unit;

    /** 认证状态：certified 已认证 / pending 待认证 / rejected 已驳回（状态机起点为 pending）。 */
    private String status;

    /** 电子防伪码；新增时生成 12 位随机码，认证后作为链上存证核验依据。 */
    private String fingerprint;

    /** 提交时间；填报落库时写入。 */
    private LocalDateTime createdAt;

    /** 填报人；取当前登录用户姓名。 */
    private String reporter;
}
