package com.carbon.trace.service;

import com.carbon.trace.entity.RegulatorOrganization;

import java.util.List;

/**
 * 监管机构服务接口
 */
public interface RegulatorOrganizationService {

    /** 查询监管机构列表（可按名称模糊过滤） */
    List<RegulatorOrganization> list(String name);

    /** 按编号查询监管机构 */
    RegulatorOrganization getById(String id);

    /** 新增监管机构，编号为空时自动生成 */
    RegulatorOrganization create(RegulatorOrganization organization);

    /** 更新监管机构 */
    RegulatorOrganization update(String id, RegulatorOrganization organization);

    /** 删除监管机构 */
    void delete(String id);
}
