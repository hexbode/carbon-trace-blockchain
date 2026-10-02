package com.carbon.trace.service;

import com.carbon.trace.entity.AuditOrganization;

import java.util.List;

/**
 * 审核机构服务
 */
public interface AuditOrganizationService {

    List<AuditOrganization> list(String name);

    AuditOrganization getById(String id);

    AuditOrganization create(AuditOrganization organization);

    AuditOrganization update(String id, AuditOrganization organization);

    void delete(String id);
}
