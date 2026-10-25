package com.carbon.trace.service;

import com.carbon.trace.entity.Enterprise;

import java.util.List;

/**
 * 企业服务。
 * 维护控排企业档案（信用代码、行业、核算范围等），供企业用户挂靠并作为排放数据的归属主体。
 * 对应表 enterprises，均为单表操作，方法本身不开启事务、无链上副作用；
 * 删除企业不会级联清理其排放/报告/认证记录，调用方需另行确认。
 * 权限：查询登录即可；新增/修改/删除由 Controller 限制为 regulator / admin。
 */
public interface EnterpriseService {

    /**
     * 按名称、行业条件查询企业列表，按编号升序。
     *
     * @param name     企业名称关键字，为空表示不过滤
     * @param industry 所属行业精确匹配，为空表示不过滤
     * @return 企业列表；无匹配时返回空列表
     */
    List<Enterprise> list(String name, String industry);

    /**
     * 查询已登记的全部行业，去重并按字典序排列，供筛选下拉使用。
     *
     * @return 行业名称列表；无数据时返回空列表
     */
    List<String> industries();

    /**
     * 按编号查询企业详情。
     *
     * @param id 企业编号（ENT + 年份 + 4 位序号）
     * @return 企业实体
     * @throws com.carbon.trace.common.ApiException 企业不存在时抛出 404
     */
    Enterprise getById(String id);

    /**
     * 新增企业；编号为空时按「ENT + 年份 + 4 位序号」自动生成，状态默认 active，注册时间默认当日。
     * 权限：仅 regulator / admin。
     *
     * @param enterprise 企业档案（名称、行业、信用代码等）
     * @return 落库后的企业实体（含生成的编号）
     */
    Enterprise create(Enterprise enterprise);

    /**
     * 按编号更新企业档案；请求体中的 id 会被路径参数覆盖。
     * 权限：仅 regulator / admin。
     *
     * @param id         企业编号
     * @param enterprise 新的企业档案
     * @return 更新后的企业实体
     * @throws com.carbon.trace.common.ApiException 企业不存在时抛出 404
     */
    Enterprise update(String id, Enterprise enterprise);

    /**
     * 按编号删除企业。
     * 仅确认记录存在，不做级联清理；权限：仅 regulator / admin。
     *
     * @param id 企业编号
     * @throws com.carbon.trace.common.ApiException 企业不存在时抛出 404
     */
    void delete(String id);
}
