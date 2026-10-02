package com.carbon.trace.service;

import com.carbon.trace.entity.EmissionSource;

import java.util.List;

/**
 * 排放源服务
 */
public interface EmissionSourceService {

    List<EmissionSource> list();

    EmissionSource getByCode(String code);
}
