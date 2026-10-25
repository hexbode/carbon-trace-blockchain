package com.carbon.trace.service;

import com.carbon.trace.entity.RegulatorOrganization;

import java.util.List;

/**
 * 监管机构服务。
 * 维护监管机构档案（区域、职责、联系人等），供监管员账号挂靠；均为单表操作，
 * 方法本身不开启事务、无链上副作用。
 * 权限：Controller 对查询与写操作均限制为 regulator / admin。
 */
public interface RegulatorOrganizationService {

    /**
     * 查询监管机构列表（可按名称模糊过滤），按编号升序。
     * 权限：仅 regulator / admin。
     *
     * @param name 机构名称关键字，为空表示查询全部
     * @return 监管机构列表；无匹配时返回空列表
     */
    List<RegulatorOrganization> list(String name);

    /**
     * 按编号查询监管机构。
     *
     * @param id 机构编号（REG + 3 位序号）
     * @return 监管机构实体
     * @throws com.carbon.trace.common.ApiException 机构不存在时抛出 404
     */
    RegulatorOrganization getById(String id);

    /**
     * 新增监管机构；先校验名称、区域、联系人、电话、地址、职责必填，
     * 编号为空时按「REG + 3 位序号」生成，状态默认 active，成立时间默认当日。
     * 权限：仅 regulator / admin。
     *
     * @param organization 监管机构档案
     * @return 落库后的监管机构实体
     * @throws com.carbon.trace.common.ApiException 必填字段缺失时抛出 400
     */
    RegulatorOrganization create(RegulatorOrganization organization);

    /**
     * 更新监管机构；同样执行必填字段校验，请求体中的 id 会被路径参数覆盖。
     * 权限：仅 regulator / admin。
     *
     * @param id           机构编号
     * @param organization 新的监管机构档案
     * @return 更新后的监管机构实体
     * @throws com.carbon.trace.common.ApiException 必填字段缺失时抛出 400；机构不存在时抛出 404
     */
    RegulatorOrganization update(String id, RegulatorOrganization organization);

    /**
     * 删除监管机构；仅确认记录存在，不校验是否仍有监管员账号挂靠。
     * 权限：仅 regulator / admin。
     *
     * @param id 机构编号
     * @throws com.carbon.trace.common.ApiException 机构不存在时抛出 404
     */
    void delete(String id);
}
