package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.EmissionSource;

/**
 * 排放源 Mapper。
 * 对应表 emission_sources（实体 {@link EmissionSource}），主键为排放源编码 code，继承 BaseMapper 获得基础 CRUD。
 */
public interface EmissionSourceMapper extends BaseMapper<EmissionSource> {
}
