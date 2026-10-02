package com.carbon.trace.service;

import com.carbon.trace.entity.EmissionFactor;

import java.util.List;

/**
 * 碳排放因子服务
 */
public interface EmissionFactorService {

    List<EmissionFactor> list(String category, String keyword);

    EmissionFactor getById(Integer id);

    EmissionFactor create(EmissionFactor factor);

    EmissionFactor update(Integer id, EmissionFactor factor);

    void delete(Integer id);
}
