package com.carbon.trace.service;

import com.carbon.trace.entity.EmissionRecord;

import java.util.List;
import java.util.Map;

/**
 * 碳排放数据记录服务
 */
public interface EmissionRecordService {

    List<EmissionRecord> list(String enterpriseId, String period, String sourceCode, String status, String keyword);

    EmissionRecord getById(String id);

    /** 新增填报：自动计算排放量、生成电子防伪码，并同步生成报告记录 */
    EmissionRecord create(Map<String, Object> body);

    void delete(String id);
}
