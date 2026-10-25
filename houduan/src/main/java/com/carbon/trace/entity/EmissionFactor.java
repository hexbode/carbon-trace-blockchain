package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 碳排放因子库表。
 * 对应表 emission_factors，主键 id 为输入型整数（IdType.INPUT）。
 * 业务职责：维护各类排放源对应的权威排放因子（依据国家标准等），供企业核算排放量时按排放源/类别选取；
 * 因子值变更通过 {@code updatedAt} 记录最近一次维护时间，{@code status} 控制是否启用。
 * 关联关系：与 emission_sources 是按排放源/类别维护的同类字典，企业填报时实际使用的因子记录在
 * emission_records.emission_factor，本表用于管理端维护与查询，不直接外键引用。
 * 字段契约：{@code factor} 为因子数值，{@code unit} 为其量纲单位，二者成对使用。
 */
@Data
@TableName("emission_factors")
public class EmissionFactor {

    /** 主键；新增时未指定则按当前记录总数 + 1 生成（应用内取号，非数据库自增）。 */
    @TableId(type = IdType.INPUT)
    private Integer id;

    /** 排放源名称 */
    private String source;

    /** 类别：化石燃料/购入能源/工业过程 */
    private String category;

    /** 排放因子取值；数值含义由 {@code unit} 字段的量纲决定。 */
    private BigDecimal factor;

    /** 排放因子单位，如 tCO₂/t煤、tCO₂/万kWh；与 {@code factor} 成对使用。 */
    private String unit;

    /** 依据标准；如国家标准、行业核算指南名称。 */
    private String standard;

    /** 最近维护时间；新增时默认当日，编辑时刷新为当日。 */
    private LocalDate updatedAt;

    /** 状态：active 启用 / inactive 停用 */
    private String status;
}
