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

    public AuditOrganizationServiceImpl(AuditOrganizationMapper auditOrganizationMapper) {
        this.auditOrganizationMapper = auditOrganizationMapper;
    }

    @Override
    public List<AuditOrganization> list(String name) {
        LambdaQueryWrapper<AuditOrganization> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            wrapper.like(AuditOrganization::getName, name);
        }
        wrapper.orderByAsc(AuditOrganization::getId);
        return auditOrganizationMapper.selectList(wrapper);
    }

    @Override
    public AuditOrganization getById(String id) {
        AuditOrganization organization = auditOrganizationMapper.selectById(id);
        if (organization == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "审核机构不存在");
        }
        return organization;
    }

    /** 新增机构：编号空时按「AUD + 自增序号」生成；状态默认 active，入驻时间默认当日 */
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

    @Override
    public AuditOrganization update(String id, AuditOrganization organization) {
        organization.setId(id);
        auditOrganizationMapper.updateById(organization);
        return getById(id);
    }

    @Override
    public void delete(String id) {
        // 先确认记录存在，避免删除不存在的记录时静默成功
        getById(id);
        auditOrganizationMapper.deleteById(id);
    }
}
