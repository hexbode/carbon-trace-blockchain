package com.carbon.trace.service;

import com.carbon.trace.entity.EmissionRecord;

import java.util.List;
import java.util.Map;

/**
 * 碳排放数据记录服务。
 * 维护企业填报的排放核算明细，是认证流程源头；新增时自动计算排放量、生成防伪码并派生报告记录，
 * 删除时同步清理关联的合规镜像与失效告警。
 */
public interface EmissionRecordService {

    /** 按企业、报告期、排放源、认证状态、关键字等条件过滤查询记录列表 */
    List<EmissionRecord> list(String enterpriseId, String period, String sourceCode, String status, String keyword);

    /** 按记录编号查询详情 */
    EmissionRecord getById(String id);

    /** 新增填报：自动计算排放量、生成电子防伪码，并同步生成报告记录 */
    EmissionRecord create(Map<String, Object> body);

    /** 按编号删除记录，并联动清理合规合计镜像与相关告警（见 AlertService） */
    void delete(String id);
}
