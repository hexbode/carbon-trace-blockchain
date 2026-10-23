package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.EmissionFactor;

/**
 * 碳排放因子 Mapper。
 * 对应表 emission_factors（实体 {@link EmissionFactor}），继承 BaseMapper 获得基础 CRUD，无自定义 SQL。
 */
public interface EmissionFactorMapper extends BaseMapper<EmissionFactor> {
}
