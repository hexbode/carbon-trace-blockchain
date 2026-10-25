package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.Alert;

/**
 * 异常告警 Mapper。
 * 对应表 alerts（实体 {@link Alert}），主键 id 为 String 类型的告警编号（ALT + 3 位序号）。
 * 继承 MyBatis-Plus BaseMapper 获得基础 CRUD，无自定义 SQL；业务层通过 LambdaQueryWrapper 组装条件，
 * 常用查询包括：按 level/status 过滤并按 createdAt 倒序；按企业 + 报告期 + 类型 + 级别统计用于写入去重；
 * 按企业 + 报告期批量删除失效告警。
 */
public interface AlertMapper extends BaseMapper<Alert> {
}
