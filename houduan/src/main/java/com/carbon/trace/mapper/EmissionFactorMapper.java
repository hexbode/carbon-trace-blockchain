package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.EmissionFactor;

/**
 * 碳排放因子 Mapper。
 * 对应表 emission_factors（实体 {@link EmissionFactor}），主键 id 为 Integer 类型（应用内取号）。
 * 继承 BaseMapper 获得基础 CRUD，无自定义 SQL；常用操作为按 category 精确过滤、
 * 按 source 模糊匹配关键字并按 id 升序，以及新增前 selectCount 统计总数用于生成主键。
 */
public interface EmissionFactorMapper extends BaseMapper<EmissionFactor> {
}
