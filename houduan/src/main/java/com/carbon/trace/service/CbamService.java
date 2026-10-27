package com.carbon.trace.service;

import com.carbon.trace.entity.CbamDeclaration;
import com.carbon.trace.entity.CbamGoods;
import com.carbon.trace.entity.CbamInstallation;
import com.carbon.trace.entity.CbamProductEmission;
import com.carbon.trace.entity.CbamVerificationReport;
import com.carbon.trace.entity.CbamVerifier;

import java.util.List;
import java.util.Map;

/**
 * CBAM 合规服务（P0）：产品目录、装置、产品级隐含排放、核查机构/报告与年度申报包。
 *
 * 设计边界：
 *   1. 排放总量一律由服务端按“产量 × 强度”重算，忽略请求中的汇总值；
 *   2. 申报包按“企业 + 年度”唯一，生成时冻结 JSON 快照；
 *   3. 仅实际排放（actual）要求存在结论为 verified 的核查报告，默认值数据不强制；
 *   4. 本阶段不写链，链上存证留给 P1。
 */
public interface CbamService {

    List<CbamGoods> listGoods(String sector, String keyword);

    CbamGoods createGoods(CbamGoods goods);

    CbamGoods updateGoods(Integer id, CbamGoods goods);

    void deleteGoods(Integer id);

    List<CbamInstallation> listInstallations(String enterpriseId);

    CbamInstallation getInstallation(Integer id);

    CbamInstallation createInstallation(CbamInstallation installation);

    CbamInstallation updateInstallation(Integer id, CbamInstallation installation);

    void deleteInstallation(Integer id);

    List<CbamProductEmission> listEmissions(String enterpriseId, Integer year, Integer goodsId);

    CbamProductEmission getEmission(Long id);

    CbamProductEmission createEmission(CbamProductEmission emission);

    CbamProductEmission updateEmission(Long id, CbamProductEmission emission);

    void deleteEmission(Long id);

    List<CbamVerifier> listVerifiers();

    CbamVerifier createVerifier(CbamVerifier verifier);

    List<CbamVerificationReport> listVerificationReports(String enterpriseId, Integer year);

    CbamVerificationReport createVerificationReport(CbamVerificationReport report);

    CbamDeclaration generateDeclaration(String enterpriseId, Integer year, String importerName);

    List<CbamDeclaration> listDeclarations(String enterpriseId, Integer year, String status);

    CbamDeclaration getDeclaration(Long id);

    String exportDeclaration(Long id);

    CbamDeclaration submitDeclaration(Long id);

    CbamDeclaration reviewDeclaration(Long id, boolean approved, String comment);

    Map<String, Object> stats(Integer year);
}
