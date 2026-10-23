package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.ReportRecord;

/**
 * 报告记录 Mapper。
 * 对应表 report_records（实体 {@link ReportRecord}），继承 BaseMapper 获得基础 CRUD，无自定义 SQL。
 */
public interface ReportRecordMapper extends BaseMapper<ReportRecord> {
}
