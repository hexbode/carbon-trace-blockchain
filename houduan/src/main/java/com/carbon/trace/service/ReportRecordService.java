package com.carbon.trace.service;

import com.carbon.trace.entity.ReportRecord;

import java.util.List;
import java.util.Map;

/**
 * 报告提交记录服务。
 * 记录企业对外报送排放报告及审核流转：审核通过与排放记录认证联动（生成/更新认证记录并上链），
 * 审核驳回后可再次申请（apply 将状态改回 pending）。
 */
public interface ReportRecordService {

    /** 按企业、审核状态、关键字过滤查询报告列表 */
    List<ReportRecord> list(String enterpriseId, String status, String keyword);

    /** 按报告编号查询详情 */
    ReportRecord getById(String id);

    /** 审核报告；若认证通过则生成/更新认证记录并上链 */
    ReportRecord audit(String id, Map<String, Object> body);

    /** 认证申请：把已驳回的报告改回 pending */
    ReportRecord apply(String id);
}
