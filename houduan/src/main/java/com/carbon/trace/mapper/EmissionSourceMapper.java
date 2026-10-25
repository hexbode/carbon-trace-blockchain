package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.EmissionSource;

/**
 * 排放源 Mapper。
 * 对应表 emission_sources（实体 {@link EmissionSource}），主键为 String 类型的排放源编码 code（业务主键）。
 * 继承 BaseMapper 获得基础 CRUD，无自定义 SQL；常用操作为按 code 升序返回全部字典项（选择列表），
 * 以及按 code 查询单个排放源及其默认排放因子（填报核算）。
 */
public interface EmissionSourceMapper extends BaseMapper<EmissionSource> {
}
