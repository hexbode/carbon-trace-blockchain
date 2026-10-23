package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.entity.EmissionFactor;
import com.carbon.trace.mapper.EmissionFactorMapper;
import com.carbon.trace.service.EmissionFactorService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 碳排放因子服务实现。
 * 无事务边界（单表操作）；新增时自动补主键（当前总数+1）、默认启用状态与更新时间，更新时刷新更新时间。
 */
@Service
public class EmissionFactorServiceImpl implements EmissionFactorService {

    private final EmissionFactorMapper emissionFactorMapper;

    public EmissionFactorServiceImpl(EmissionFactorMapper emissionFactorMapper) {
        this.emissionFactorMapper = emissionFactorMapper;
    }

    @Override
    public List<EmissionFactor> list(String category, String keyword) {
        LambdaQueryWrapper<EmissionFactor> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(category)) {
            wrapper.eq(EmissionFactor::getCategory, category);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(EmissionFactor::getSource, keyword);
        }
        wrapper.orderByAsc(EmissionFactor::getId);
        return emissionFactorMapper.selectList(wrapper);
    }

    @Override
    public EmissionFactor getById(Integer id) {
        EmissionFactor factor = emissionFactorMapper.selectById(id);
        if (factor == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "排放因子不存在");
        }
        return factor;
    }

    /** 新增因子：主键空时按总数+1 生成，状态默认 active，更新时间默认当日 */
    @Override
    public EmissionFactor create(EmissionFactor factor) {
        if (factor.getId() == null) {
            long count = emissionFactorMapper.selectCount(null);
            factor.setId((int) count + 1);
        }
        if (!StringUtils.hasText(factor.getStatus())) {
            factor.setStatus("active");
        }
        // 更新时间由后端补齐为创建当天
        if (factor.getUpdatedAt() == null) {
            factor.setUpdatedAt(LocalDate.now());
        }
        emissionFactorMapper.insert(factor);
        return factor;
    }

    @Override
    public EmissionFactor update(Integer id, EmissionFactor factor) {
        factor.setId(id);
        // 编辑时刷新更新时间为当天
        factor.setUpdatedAt(LocalDate.now());
        emissionFactorMapper.updateById(factor);
        return getById(id);
    }

    @Override
    public void delete(Integer id) {
        // 先确认记录存在，避免删除不存在的记录时静默成功
        getById(id);
        emissionFactorMapper.deleteById(id);
    }
}
