package com.carbon.trace.service;

import com.carbon.trace.entity.CertificationRecord;
import com.carbon.trace.entity.ReportRecord;

import java.util.List;
import java.util.Map;

/**
 * 认证记录服务
 */
public interface CertificationService {

    List<CertificationRecord> list(String enterpriseId, String status, String keyword);

    CertificationRecord getById(String id);

    /** 按电子防伪码精确查询（公共端；认证编号不对外提供查询） */
    CertificationRecord getByFingerprint(String fingerprint);

    /** 公共端固定示例证书（固定 3 张演示证书，无参数、不可遍历） */
    List<CertificationRecord> listSamples();

    /** 撤销认证 */
    CertificationRecord revoke(String id, Map<String, Object> body);

    /** 校验防伪码/交易哈希，返回 {valid, msg, cert} */
    Map<String, Object> verify(String hash);

    /** 认证通过时生成/更新认证记录并上链（供报告审核调用） */
    CertificationRecord certify(ReportRecord report);
}
