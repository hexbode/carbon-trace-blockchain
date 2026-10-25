package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.entity.EmissionSource;
import com.carbon.trace.mapper.EmissionSourceMapper;
import com.carbon.trace.service.EmissionSourceService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 排放源服务实现。
 * 字典只读服务：按编码升序返回全部排放源，按编码精确查询，供企业填报选取排放源与默认因子。
 */
@Service
public class EmissionSourceServiceImpl implements EmissionSourceService {

    private final EmissionSourceMapper emissionSourceMapper;

    /**
     * 构造器注入依赖，由 Spring 容器创建，业务代码不应手工实例化。
     *
     * @param emissionSourceMapper 排放源 Mapper，本类为只读字典服务，全部查询都经由此处
     */
    public EmissionSourceServiceImpl(EmissionSourceMapper emissionSourceMapper) {
        this.emissionSourceMapper = emissionSourceMapper;
    }

    /**
     * 查询全部排放源字典，按排放源编码升序返回。
     *
     * 字典为公共只读数据（无状态、无写接口），登录用户即可访问，无需按角色过滤。
     *
     * @return 排放源列表；表为空时返回空列表
     */
    @Override
    public List<EmissionSource> list() {
        return emissionSourceMapper.selectList(new LambdaQueryWrapper<EmissionSource>().orderByAsc(EmissionSource::getCode));
    }

    /**
     * 按排放源编码精确查询（code 即主键）。
     *
     * 当前未由控制器暴露；语义上是字典取值（如按编码取默认因子），
     * 因此未命中返回 null 而不抛 404，由调用方自行判空。
     *
     * @param code 排放源编码
     * @return 匹配的排放源；编码不存在时返回 null
     */
    @Override
    public EmissionSource getByCode(String code) {
        return emissionSourceMapper.selectById(code);
    }
}
