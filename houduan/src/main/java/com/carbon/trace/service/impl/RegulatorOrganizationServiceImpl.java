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
 * 监管机构服务实现
 */
@Service
public class RegulatorOrganizationServiceImpl implements RegulatorOrganizationService {

    private final RegulatorOrganizationMapper regulatorOrganizationMapper;

    public RegulatorOrganizationServiceImpl(RegulatorOrganizationMapper regulatorOrganizationMapper) {
        this.regulatorOrganizationMapper = regulatorOrganizationMapper;
    }

    @Override
    public List<RegulatorOrganization> list(String name) {
        LambdaQueryWrapper<RegulatorOrganization> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            wrapper.like(RegulatorOrganization::getName, name);
        }
        wrapper.orderByAsc(RegulatorOrganization::getId);
        return regulatorOrganizationMapper.selectList(wrapper);
    }

    @Override
    public RegulatorOrganization getById(String id) {
        RegulatorOrganization organization = regulatorOrganizationMapper.selectById(id);
        if (organization == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "监管机构不存在");
        }
        return organization;
    }

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

    @Override
    public RegulatorOrganization update(String id, RegulatorOrganization organization) {
        validateRequired(organization);
        organization.setId(id);
        regulatorOrganizationMapper.updateById(organization);
        return getById(id);
    }

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
