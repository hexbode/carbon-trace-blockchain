package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 排放源字典表。
 * 对应表 emission_sources，主键 code 为业务排放源编码（IdType.INPUT）。
 * 业务职责：定义企业可填报的排放源种类及其默认排放因子，作为排放记录的排放源字典与核算默认值来源。
 * 字段契约：{@code factor} 为该排放源的默认排放因子，{@code unit} 为其量纲单位。
 */
@Data
@TableName("emission_sources")
public class EmissionSource {

    /** 排放源编码 */
    @TableId(type = IdType.INPUT)
    private String code;

    /** 排放源名称 */
    private String name;

    /** 默认排放因子；企业填报未指定因子时可作为核算默认值。 */
    private BigDecimal factor;

    /** 排放因子单位 */
    private String unit;
}
