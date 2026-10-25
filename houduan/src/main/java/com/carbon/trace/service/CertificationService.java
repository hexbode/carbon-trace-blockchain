package com.carbon.trace.service;

import com.carbon.trace.entity.CertificationRecord;
import com.carbon.trace.entity.ReportRecord;

import java.util.List;
import java.util.Map;

/**
 * 认证记录服务。
 * 负责认证凭证的查询、撤销与防伪核验；认证凭证在报告审核通过时生成并上链存证，
 * 对外（公共端）仅暴露防伪码查询，不直接暴露内部认证编号，防止遍历。
 * 核验以链上存证为唯一依据，业务库只提供审核意见等展示字段；证书状态以链上 valid/revoked/invalid 为准。
 * 链上写入：撤销（revoke）与认证（certify）均交由 {@code ChainSyncService} 处理——有事务时在提交后写链，
 * 无事务时立即写链；失败按退避重试，最终失败生成 high 级告警。
 * 权限：列表/详情受企业归属限制；撤销仅 regulator / admin；防伪查询与核验接口免登录。
 */
public interface CertificationService {

    /**
     * 按企业、状态、关键字过滤查询认证记录列表，按认证时间倒序。
     * 关键字匹配认证编号、企业名称与审核人；链上交易哈希/区块高度仅从本地定位缓存回填，不逐条回链。
     *
     * @param enterpriseId 企业编号，为空表示不过滤
     * @param status       认证状态（valid/revoked），为空表示不过滤
     * @param keyword      关键字，为空表示不过滤
     * @return 认证记录列表；无匹配时返回空列表
     */
    List<CertificationRecord> list(String enterpriseId, String status, String keyword);

    /**
     * 按认证编号查询详情，并实时回链回填电子防伪码、交易哈希与区块高度。
     *
     * @param id 认证编号（CR + 6 位序号，三表同号）
     * @return 认证记录实体
     * @throws com.carbon.trace.common.ApiException 记录不存在时抛出 404
     */
    CertificationRecord getById(String id);

    /**
     * 按电子防伪码精确查询（公共端：认证编号不对外提供查询）。
     * 先按防伪码定位链上凭证，再由排放编号关联本地认证记录，展示字段以链上数据为准。
     * 权限：免登录（公共扫码核验）。
     *
     * @param fingerprint 电子防伪码
     * @return 认证记录实体（防伪码、交易哈希、区块高度已回填）
     * @throws com.carbon.trace.common.ApiException 防伪码为空或链上未匹配时抛出 404
     */
    CertificationRecord getByFingerprint(String fingerprint);

    /**
     * 公共端固定示例证书（固定 3 张演示证书，无参数、不可遍历）。
     * 读取 CR000001～CR000003 并实时回填链上交易信息，避免公众通过参数遍历全部认证记录。
     *
     * @return 示例认证记录列表（最多 3 条）
     */
    List<CertificationRecord> listSamples();

    /**
     * 撤销认证：写入状态 revoked、撤销原因与撤销时间，并通过 {@code ChainSyncService} 把链上状态置为
     * REVOKED（终局状态）；本方法未开启事务，链上撤销通常立即执行。
     * 链上撤销失败会重试，最终失败生成 high 级链上同步告警；本地撤销结果不回滚。
     * 权限：仅 regulator / admin。
     *
     * @param id   认证编号
     * @param body 撤销数据，reason 必填，detail 可选（会拼接为「原因：说明」）
     * @return 撤销后的认证记录
     * @throws com.carbon.trace.common.ApiException 撤销原因为空时抛出 400；认证记录不存在时抛出 404
     */
    CertificationRecord revoke(String id, Map<String, Object> body);

    /**
     * 校验电子防伪码或交易哈希，以链上存证为唯一依据。
     * 已撤销/已作废的证书仍返回 cert 供前端展示，但 valid=false 并给出对应原因；本方法只读，不写库、不写链。
     * 权限：免登录（公共核验）。
     *
     * @param hash 电子防伪码或链上交易哈希
     * @return 结果 Map：valid 是否有效、msg 提示文案、cert 匹配到的认证记录（未匹配时为 null）
     */
    Map<String, Object> verify(String hash);

    /**
     * 认证通过时生成/更新认证记录并上链（供报告审核调用）。
     * 认证编号、排放记录编号、报告编号三表同号；已存在则更新，不存在则插入。
     * 事务：本方法开启事务，认证记录落库后通过 {@code ChainSyncService.afterCommit} 在提交后写链，
     * 避免事务回滚在链上留下孤儿存证；链上合规快照位掩码随存证一起上链。
     * 链上失败会重试，最终失败生成 high 级告警，不影响已提交的认证记录。
     * 权限：由报告审核流程内部调用，不对应独立对外接口。
     *
     * @param report 已审核通过的报告记录（提供企业、报告期、排放量、防伪码、审核人与意见）
     * @return 生成或更新后的认证记录
     */
    CertificationRecord certify(ReportRecord report);
}
