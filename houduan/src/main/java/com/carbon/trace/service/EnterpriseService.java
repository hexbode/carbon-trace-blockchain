package com.carbon.trace.service;

import com.carbon.trace.entity.Enterprise;

import java.util.List;

/**
 * 企业服务。
 * 维护控排企业档案（信用代码、行业、核算范围等），供企业用户挂靠并作为排放数据的归属主体。
 */
public interface EnterpriseService {

    /** 按名称、行业条件查询企业列表 */
    List<Enterprise> list(String name, String industry);

    /** 查询已登记的全部行业（去重，供筛选下拉） */
    List<String> industries();

    /** 按编号查询企业详情 */
    Enterprise getById(String id);

    /** 新增企业（编号自动生成） */
    Enterprise create(Enterprise enterprise);

    /** 按编号更新企业档案 */
    Enterprise update(String id, Enterprise enterprise);

    /** 按编号删除企业 */
    void delete(String id);
}
