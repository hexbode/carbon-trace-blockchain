package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.EmissionRecord;

/**
 * 碳排放记录 Mapper。
 * 对应表 emission_records（实体 {@link EmissionRecord}），继承 BaseMapper 获得基础 CRUD，
 * 无自定义 SQL；排放量合计、合规输入聚合均由业务层内存完成。
 */
public interface EmissionRecordMapper extends BaseMapper<EmissionRecord> {
}
