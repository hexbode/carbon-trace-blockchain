package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.entity.RegulatorOrganization;
import com.carbon.trace.mapper.RegulatorOrganizationMapper;
import com.carbon.trace.service.RegulatorOrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 监管机构服务实现。
 * 无事务边界（单表操作）；新增/更新前先做必填字段前置校验（拦截 400 而非让 SQL 约束以 500 暴露），
 * 新增时编号空则按「REG + 3 位序号」生成，成立时间默认当日。
 */
@Service
public class RegulatorOrganizationServiceImpl implements RegulatorOrganizationService {

    private final RegulatorOrganizationMapper regulatorOrganizationMapper;

    /**
     * 构造器注入依赖，由 Spring 容器创建，业务代码不应手工实例化。
     *
     * @param regulatorOrganizationMapper 监管机构 Mapper，本类全部查询与增删改都经由此处
     */
    public RegulatorOrganizationServiceImpl(RegulatorOrganizationMapper regulatorOrganizationMapper) {
        this.regulatorOrganizationMapper = regulatorOrganizationMapper;
    }

    /**
     * 按机构名称模糊查询监管机构列表，按编号升序返回。
     *
     * 权限：监管机构信息仅 regulator / admin 可见，控制器对本查询同样做了角色限制（其他角色 403），
     * 与企业、审核机构的「登录即可查」不同。
     *
     * @param name 机构名称关键字；空白时不参与过滤
     * @return 监管机构列表
     */
    @Override
    public List<RegulatorOrganization> list(String name) {
        LambdaQueryWrapper<RegulatorOrganization> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            wrapper.like(RegulatorOrganization::getName, name);
        }
        wrapper.orderByAsc(RegulatorOrganization::getId);
        return regulatorOrganizationMapper.selectList(wrapper);
    }

    /**
     * 按机构编号查询监管机构。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param id 机构编号（业务主键）
     * @return 监管机构实体
     * @throws ApiException 机构不存在时抛 404
     */
    @Override
    public RegulatorOrganization getById(String id) {
        RegulatorOrganization organization = regulatorOrganizationMapper.selectById(id);
        if (organization == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "监管机构不存在");
        }
        return organization;
    }

    /**
     * 新增监管机构：先必填校验，再补编号/状态/成立时间默认值后落库。
     *
     * 编号规则为「REG + 3 位序号」，序号取当前记录总数 + 1，删除后重算或并发新增可能撞号，由主键约束兜底；
     * 必填项（名称、区域、联系人、电话、地址、监管职责）在写入前拦截为 400，避免库约束异常以 500 暴露；
     * status 为空默认 active，createdAt 为空补当日。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param organization 监管机构实体
     * @return 落库后的监管机构实体
     * @throws ApiException 提交内容为空或任一必填字段空白时抛 400
     */
    @Override
    public RegulatorOrganization create(RegulatorOrganization organization) {
        validateRequired(organization);
        if (!StringUtils.hasText(organization.getId())) {
            long count = regulatorOrganizationMapper.selectCount(null);
            organization.setId("REG" + String.format("%03d", count + 1));
        }
        if (!StringUtils.hasText(organization.getStatus())) {
            organization.setStatus("active");
        }
        // 成立时间由后端补齐：created_at 可为空，但前端表单不提交该字段
        if (organization.getCreatedAt() == null) {
            organization.setCreatedAt(LocalDate.now());
        }
        regulatorOrganizationMapper.insert(organization);
        return organization;
    }

    /**
     * 按编号更新监管机构：必填校验通过后整体覆盖可提交字段。
     *
     * 以路径编号覆盖请求体编号；必填字段因校验保证非空，会按提交值覆盖；
     * 状态等可空字段沿用 MyBatis-Plus 默认策略（null 不更新，保持原值）。
     * 更新后回查，不存在时抛 404。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param id           机构编号（以路径参数为准）
     * @param organization 待更新字段
     * @return 更新后的监管机构实体
     * @throws ApiException 任一必填字段空白抛 400；机构不存在抛 404
     */
    @Override
    public RegulatorOrganization update(String id, RegulatorOrganization organization) {
        validateRequired(organization);
        organization.setId(id);
        regulatorOrganizationMapper.updateById(organization);
        return getById(id);
    }

    /**
     * 按编号删除监管机构。
     *
     * 先校验存在性，避免静默成功；本表未被其他表的外键引用，删除只受本表主键约束。
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
        regulatorOrganizationMapper.deleteById(id);
    }

    /**
     * 必填字段校验：与数据库 NOT NULL 约束保持一致，
     * 在写入前拦截并返回 400，避免 SQL 约束异常以 500 暴露给前端。
     */
    private void validateRequired(RegulatorOrganization organization) {
        if (organization == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "提交内容不能为空");
        }
        if (!StringUtils.hasText(organization.getName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "机构名称不能为空");
        }
        if (!StringUtils.hasText(organization.getRegion())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "所属区域不能为空");
        }
        if (!StringUtils.hasText(organization.getContact())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "联系人不能为空");
        }
        if (!StringUtils.hasText(organization.getPhone())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "联系电话不能为空");
        }
        if (!StringUtils.hasText(organization.getAddress())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "办公地址不能为空");
        }
        if (!StringUtils.hasText(organization.getScope())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "监管职责不能为空");
        }
    }
}
