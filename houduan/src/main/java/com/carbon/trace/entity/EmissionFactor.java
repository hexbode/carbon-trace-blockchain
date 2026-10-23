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
 * 字段契约：{@code factor} 为因子数值，{@code unit} 为其量纲单位，二者成对使用。
 */
@Data
@TableName("emission_factors")
public class EmissionFactor {

    /** 主键 */
    @TableId(type = IdType.INPUT)
    private Integer id;

    /** 排放源名称 */
    private String source;

    /** 类别：化石燃料/购入能源/工业过程 */
    private String category;

    /** 排放因子取值；数值含义由 {@code unit} 字段的量纲决定。 */
    private BigDecimal factor;

    /** 单位 */
    private String unit;

    /** 依据标准 */
    private String standard;

    /** 更新时间 */
    private LocalDate updatedAt;

    /** 状态：active 启用 / inactive 停用 */
    private String status;
}
