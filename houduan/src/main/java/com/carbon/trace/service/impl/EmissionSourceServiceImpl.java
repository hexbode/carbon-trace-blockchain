package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.entity.EmissionSource;
import com.carbon.trace.mapper.EmissionSourceMapper;
import com.carbon.trace.service.EmissionSourceService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 排放源服务实现
 */
@Service
public class EmissionSourceServiceImpl implements EmissionSourceService {

    private final EmissionSourceMapper emissionSourceMapper;

    public EmissionSourceServiceImpl(EmissionSourceMapper emissionSourceMapper) {
        this.emissionSourceMapper = emissionSourceMapper;
    }

    @Override
    public List<EmissionSource> list() {
        return emissionSourceMapper.selectList(new LambdaQueryWrapper<EmissionSource>().orderByAsc(EmissionSource::getCode));
    }

    @Override
    public EmissionSource getByCode(String code) {
        return emissionSourceMapper.selectById(code);
    }
}
