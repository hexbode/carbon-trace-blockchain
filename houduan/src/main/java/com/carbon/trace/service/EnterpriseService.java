package com.carbon.trace.service;

import com.carbon.trace.entity.Enterprise;

import java.util.List;

/**
 * 企业服务
 */
public interface EnterpriseService {

    List<Enterprise> list(String name, String industry);

    List<String> industries();

    Enterprise getById(String id);

    Enterprise create(Enterprise enterprise);

    Enterprise update(String id, Enterprise enterprise);

    void delete(String id);
}
