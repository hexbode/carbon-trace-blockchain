package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.ReportRecord;

/**
 * 报告记录 Mapper。
 * 对应表 report_records（实体 {@link ReportRecord}），主键 id 为 String 报告编号（CR + 6 位序号，与排放记录、
 * 认证记录三表同号）。
 * 继承 BaseMapper 获得基础 CRUD，无自定义 SQL；常用查询包括：按 enterpriseId/status 过滤、
 * 关键字匹配 id/enterpriseName/sourceName 并按 submittedAt 倒序；按 emissionId 删除联动清理。
 */
public interface ReportRecordMapper extends BaseMapper<ReportRecord> {
}
