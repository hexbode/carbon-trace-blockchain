package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.RegulatorOrganization;

/**
 * 监管机构 Mapper。
 * 对应表 regulator_organizations（实体 {@link RegulatorOrganization}），主键 id 为 String 机构编号（REG + 3 位序号）。
 * 继承 BaseMapper 获得基础 CRUD，无自定义 SQL；常用操作为按 name 模糊查询并按 id 升序、
 * 按 id 查询详情，以及新增前 selectCount 统计总数用于生成编号。
 */
public interface RegulatorOrganizationMapper extends BaseMapper<RegulatorOrganization> {
}
