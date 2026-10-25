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
 * 关联关系：emission_records.source_code 引用本表 code；排放记录按本表 factor 计算排放量，
 * 并把本表 unit 中斜杠后的部分解析为活动数据单位。
 * 字段契约：{@code factor} 为该排放源的默认排放因子，{@code unit} 为其量纲单位。
 */
@Data
@TableName("emission_sources")
public class EmissionSource {

    /** 排放源编码；同时作为本表主键，emission_records.source_code 引用该值。 */
    @TableId(type = IdType.INPUT)
    private String code;

    /** 排放源名称 */
    private String name;

    /** 默认排放因子；企业填报未指定因子时可作为核算默认值。 */
    private BigDecimal factor;

    /** 排放因子单位；格式为「tCO₂/活动数据单位」，如 tCO₂/t煤。 */
    private String unit;
}
