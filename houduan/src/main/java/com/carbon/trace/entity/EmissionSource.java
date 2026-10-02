package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 排放源字典表
 */
@Data
@TableName("emission_sources")
public class EmissionSource {

    /** 排放源编码 */
    @TableId(type = IdType.INPUT)
    private String code;

    /** 排放源名称 */
    private String name;

    /** 默认排放因子 */
    private BigDecimal factor;

    /** 排放因子单位 */
    private String unit;
}
