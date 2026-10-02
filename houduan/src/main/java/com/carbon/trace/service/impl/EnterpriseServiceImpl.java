package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.entity.Enterprise;
import com.carbon.trace.mapper.EnterpriseMapper;
import com.carbon.trace.service.EnterpriseService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.carbon.trace.common.ApiException;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 企业服务实现
 */
@Service
public class EnterpriseServiceImpl implements EnterpriseService {

    private final EnterpriseMapper enterpriseMapper;

    public EnterpriseServiceImpl(EnterpriseMapper enterpriseMapper) {
        this.enterpriseMapper = enterpriseMapper;
    }

    @Override
    public List<Enterprise> list(String name, String industry) {
        LambdaQueryWrapper<Enterprise> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            wrapper.like(Enterprise::getName, name);
        }
        if (StringUtils.hasText(industry)) {
            wrapper.eq(Enterprise::getIndustry, industry);
        }
        wrapper.orderByAsc(Enterprise::getId);
        return enterpriseMapper.selectList(wrapper);
    }

    @Override
    public List<String> industries() {
        return enterpriseMapper.selectList(null).stream()
                .map(Enterprise::getIndustry)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public Enterprise getById(String id) {
        Enterprise enterprise = enterpriseMapper.selectById(id);
        if (enterprise == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "企业不存在");
        }
        return enterprise;
    }

    @Override
    public Enterprise create(Enterprise enterprise) {
        if (!StringUtils.hasText(enterprise.getId())) {
            long count = enterpriseMapper.selectCount(null);
            enterprise.setId("ENT" + Year.now().getValue() + String.format("%04d", count + 1));
        }
        if (!StringUtils.hasText(enterprise.getStatus())) {
            enterprise.setStatus("active");
        }
        // 注册时间由后端补齐为创建当天
        if (enterprise.getRegisteredAt() == null) {
            enterprise.setRegisteredAt(LocalDate.now());
        }
        enterpriseMapper.insert(enterprise);
        return enterprise;
    }

    @Override
    public Enterprise update(String id, Enterprise enterprise) {
        enterprise.setId(id);
        enterpriseMapper.updateById(enterprise);
        return getById(id);
    }

    @Override
    public void delete(String id) {
        // 先确认记录存在，避免删除不存在的记录时静默成功
        getById(id);
        enterpriseMapper.deleteById(id);
    }
}
