package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.AuditOrganization;

/**
 * 审核机构 Mapper。
 * 对应表 audit_organizations（实体 {@link AuditOrganization}），继承 BaseMapper 获得基础 CRUD，无自定义 SQL。
 */
public interface AuditOrganizationMapper extends BaseMapper<AuditOrganization> {
}
