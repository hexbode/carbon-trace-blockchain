package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 碳排放因子库表
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

    /** 排放因子取值 */
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
