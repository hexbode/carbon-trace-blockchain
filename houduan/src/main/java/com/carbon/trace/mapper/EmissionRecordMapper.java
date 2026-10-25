package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.EmissionRecord;

/**
 * 碳排放记录 Mapper。
 * 对应表 emission_records（实体 {@link EmissionRecord}），主键 id 为 String 排放记录编号（CR + 6 位序号）。
 * 继承 BaseMapper 获得基础 CRUD，无自定义 SQL；常用查询包括：按 enterpriseId、period、sourceCode、status 过滤，
 * 关键字匹配 id/sourceName/reporter 并按 createdAt 倒序；按 id 集合批量查询用于报告展示补全；
 * 按企业 + 年度或报告期查询后由业务层内存聚合排放量（合规判定、统计图表）。
 */
public interface EmissionRecordMapper extends BaseMapper<EmissionRecord> {
}
