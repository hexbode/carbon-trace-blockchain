package com.carbon.trace.service;

import com.carbon.trace.entity.ReportRecord;

import java.util.List;
import java.util.Map;

/**
 * 报告提交记录服务
 */
public interface ReportRecordService {

    List<ReportRecord> list(String enterpriseId, String status, String keyword);

    ReportRecord getById(String id);

    /** 审核报告；若认证通过则生成/更新认证记录并上链 */
    ReportRecord audit(String id, Map<String, Object> body);

    /** 认证申请：把已驳回的报告改回 pending */
    ReportRecord apply(String id);
}
