package com.carbon.trace.service;

import com.carbon.trace.entity.AuditOrganization;

import java.util.List;

/**
 * 审核机构服务。
 * 维护具备认证资质的第三方审核机构档案，供审核员账号挂靠与认证认领；编号为空时自动生成。
 */
public interface AuditOrganizationService {

    /** 按名称模糊查询机构列表 */
    List<AuditOrganization> list(String name);

    /** 按编号查询机构详情 */
    AuditOrganization getById(String id);

    /** 新增机构（编号等业务主键自动生成） */
    AuditOrganization create(AuditOrganization organization);

    /** 按编号更新机构档案 */
    AuditOrganization update(String id, AuditOrganization organization);

    /** 按编号删除机构 */
    void delete(String id);
}
