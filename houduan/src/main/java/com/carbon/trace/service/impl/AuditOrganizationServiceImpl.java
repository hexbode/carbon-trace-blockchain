package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.entity.AuditOrganization;
import com.carbon.trace.mapper.AuditOrganizationMapper;
import com.carbon.trace.service.AuditOrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 审核机构服务实现。
 * 无事务边界（单表操作）；新增时自动生成机构编号（AUD + 3 位序号）、补齐默认状态与入驻时间，
 * 删除/更新前先确认记录存在，避免静默成功或空更新。
 */
@Service
public class AuditOrganizationServiceImpl implements AuditOrganizationService {

    private final AuditOrganizationMapper auditOrganizationMapper;

    /**
     * 构造器注入依赖，由 Spring 容器创建，业务代码不应手工实例化。
     *
     * @param auditOrganizationMapper 审核机构 Mapper，本类全部查询与增删改都经由此处
     */
    public AuditOrganizationServiceImpl(AuditOrganizationMapper auditOrganizationMapper) {
        this.auditOrganizationMapper = auditOrganizationMapper;
    }

    /**
     * 按机构名称模糊查询审核机构列表，按编号升序返回。
     *
     * 权限：控制器仅要求登录，服务层不鉴权。
     *
     * @param name 机构名称关键字；空白时不参与过滤
     * @return 审核机构列表
     */
    @Override
    public List<AuditOrganization> list(String name) {
        LambdaQueryWrapper<AuditOrganization> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            wrapper.like(AuditOrganization::getName, name);
        }
        wrapper.orderByAsc(AuditOrganization::getId);
        return auditOrganizationMapper.selectList(wrapper);
    }

    /**
     * 按机构编号查询审核机构。
     *
     * @param id 机构编号（业务主键）
     * @return 审核机构实体
     * @throws ApiException 机构不存在时抛 404
     */
    @Override
    public AuditOrganization getById(String id) {
        AuditOrganization organization = auditOrganizationMapper.selectById(id);
        if (organization == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "审核机构不存在");
        }
        return organization;
    }

    /**
     * 新增审核机构：编号空则自动生成，状态默认 active，入驻时间默认当日。
     *
     * 编号规则为「AUD + 3 位序号」，序号取当前记录总数 + 1，删除后重算或并发新增可能与已有编号冲突，
     * 由主键约束兜底；调用方也可提交自定义编号。
     * 机构名称等非空字段仅依赖库表约束，本方法不做前置校验（与监管机构不同）。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param organization 审核机构实体（name 为库表非空字段）
     * @return 落库后的审核机构实体
     */
    @Override
    public AuditOrganization create(AuditOrganization organization) {
        if (!StringUtils.hasText(organization.getId())) {
            long count = auditOrganizationMapper.selectCount(null);
            organization.setId("AUD" + String.format("%03d", count + 1));
        }
        if (!StringUtils.hasText(organization.getStatus())) {
            organization.setStatus("active");
        }
        // 入驻时间由后端补齐为创建当天
        if (organization.getCreatedAt() == null) {
            organization.setCreatedAt(LocalDate.now());
        }
        auditOrganizationMapper.insert(organization);
        return organization;
    }

    /**
     * 按编号更新审核机构档案。
     *
     * 以路径编号覆盖请求体编号，防止改写其他机构；MyBatis-Plus 默认忽略 null 字段，
     * 未提交的字段保持原值；更新后回查，不存在时抛 404。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param id           机构编号（以路径参数为准）
     * @param organization 待更新字段
     * @return 更新后的审核机构实体
     * @throws ApiException 机构不存在时抛 404
     */
    @Override
    public AuditOrganization update(String id, AuditOrganization organization) {
        organization.setId(id);
        auditOrganizationMapper.updateById(organization);
        return getById(id);
    }

    /**
     * 按编号删除审核机构。
     *
     * 先校验存在性，避免静默成功；机构的 id 被用户账号 org_id 外键引用时，
     * 删除会被数据库拒绝（需先调整审核员归属），本方法不做引用预检。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param id 机构编号
     * @throws ApiException 机构不存在时抛 404
     */
    @Override
    public void delete(String id) {
        // 先确认记录存在，避免删除不存在的记录时静默成功
        getById(id);
        auditOrganizationMapper.deleteById(id);
    }
}
