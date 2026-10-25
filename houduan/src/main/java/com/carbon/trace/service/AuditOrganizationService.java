package com.carbon.trace.service;

import com.carbon.trace.entity.AuditOrganization;

import java.util.List;

/**
 * 审核机构服务。
 * 维护具备认证资质的第三方审核机构档案，供审核员账号挂靠与认证认领；编号为空时自动生成。
 * 对应表 audit_organizations，均为单表操作，方法本身不开启事务、无链上副作用。
 * 权限：新增/修改/删除由 Controller 限制为 regulator / admin；查询登录即可。
 */
public interface AuditOrganizationService {

    /**
     * 按名称模糊查询机构列表，按编号升序。
     *
     * @param name 机构名称关键字，为空表示查询全部
     * @return 机构列表；无匹配时返回空列表
     */
    List<AuditOrganization> list(String name);

    /**
     * 按编号查询机构详情。
     *
     * @param id 机构编号（AUD + 3 位序号）
     * @return 机构实体
     * @throws com.carbon.trace.common.ApiException 机构不存在时抛出 404
     */
    AuditOrganization getById(String id);

    /**
     * 新增机构；编号为空时按「AUD + 3 位序号」自动生成，状态默认 active，入驻时间默认当日。
     * 权限：仅 regulator / admin。
     *
     * @param organization 机构档案（名称等业务字段）
     * @return 落库后的机构实体（含生成的编号）
     */
    AuditOrganization create(AuditOrganization organization);

    /**
     * 按编号更新机构档案；请求体中的 id 会被路径参数覆盖。
     * 权限：仅 regulator / admin。
     *
     * @param id           机构编号
     * @param organization 新的机构档案
     * @return 更新后的机构实体
     * @throws com.carbon.trace.common.ApiException 机构不存在时抛出 404
     */
    AuditOrganization update(String id, AuditOrganization organization);

    /**
     * 按编号删除机构。
     * 仅确认机构存在，不校验是否仍有审核员账号挂靠；权限：仅 regulator / admin。
     *
     * @param id 机构编号
     * @throws com.carbon.trace.common.ApiException 机构不存在时抛出 404
     */
    void delete(String id);
}
