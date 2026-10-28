package com.carbon.trace.chain;

import com.carbon.trace.chain.model.ChainBlockView;
import com.carbon.trace.chain.model.ChainCertCount;
import com.carbon.trace.chain.model.ChainCertificate;
import com.carbon.trace.chain.model.CertifyCommand;
import com.carbon.trace.chain.model.ComplianceInput;
import com.carbon.trace.chain.model.ComplianceResult;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 链网关：业务层与「区块链」交互的唯一抽象。
 *
 * 实现有两套：
 *  - {@code WebaseChainGateway}：真实 FISCO BCOS 联盟链（调用 WeBASE-Front REST 接口 + 智能合约），生产使用；
 *  - {@code StubChainGateway}  ：离线开发桩，仅用于本机无 WeBASE 时保证应用可启动与接口回归。
 *
 * 两套实现共同遵守的契约：
 *  - 金额以 {@link BigDecimal} 出入，定标（排放量 ×100、因子 ×10000）由网关内部完成，
 *    调用方不应预先放大，否则链上数值会再放大两个/四个数量级；
 *  - 链上数据只追加不删除：撤销（REVOKED）与作废（INVALID）是终局状态，不能用删除或覆盖表达；
 *  - 查询以 {@link Optional#empty()} 表示「链上不存在」，不视为错误；
 *    链不可达、合约 revert、回执失败等基础设施/业务异常统一抛出
 *    {@link com.carbon.trace.common.ApiException}，由上层转换为 HTTP 响应；
 *  - 接口本身不做本地库与链的分布式事务：写链成功而本地事务回滚时以链为准，
 *    两侧差异由 {@link ChainIntegrityChecker} 对账暴露。
 *
 * 实现选择由 {@code carbon.chain.mode} 决定（见 {@code ChainGatewayConfig}），
 * 同一进程只会装配一个实现，因此调用方无需处理多实例路由。
 */
public interface ChainGateway {

    // ---------------- 认证存证（写） ----------------

    /**
     * 认证上链：写入一条新的有效存证，并把交易哈希与区块高度回填到返回值。
     *
     * 同一认证编号重复认证时，实现按合约规则处理（已撤销/已作废的编号会被拒绝），
     * 调用方应先做幂等判断，不能把本方法当作「更新」使用。
     *
     * @param command 认证要素；{@code certId}、{@code fingerprint} 为空串会被合约拒绝，
     *                {@code amount} 允许为 null（按 0 处理）
     * @return 链上凭证视图，含 txHash 与 blockIndex；stub 模式返回伪哈希
     * @throws com.carbon.trace.common.ApiException 链不可达、合约 revert（如防伪码重复、编号已撤销）
     *                                              或交易回执失败时抛出
     */
    ChainCertificate certify(CertifyCommand command);

    /**
     * 监管撤销：把有效存证翻转为终局 REVOKED，撤销后不可再认证、不可再撤销。
     *
     * @param certId 认证编号（不存在时合约 revert，映射为 404）
     * @param reason 撤销原因，写入链上供追溯展示，可为 null（按空串处理）
     * @throws com.carbon.trace.common.ApiException 存证不存在、无写权限或链不可达时抛出
     */
    void revoke(String certId, String reason);

    /**
     * 排放记录被删除 → 链上作废：链上不可删除，仅把状态标记为 INVALID。
     *
     * 未认证过的排放记录不会产生存证，实现应静默忽略（与合约返回 false 的语义一致），
     * 不应因此向前端报错。
     *
     * @param emissionId 排放记录编号（与认证编号同号）
     * @param reason     作废原因，可为 null
     * @throws com.carbon.trace.common.ApiException 链不可达或合约执行失败时抛出
     */
    void invalidateByEmissionId(String emissionId, String reason);

    // ---------------- 认证存证（读 / 核验） ----------------

    /**
     * 按电子防伪码精确查询。
     *
     * 匹配大小写敏感且只去除首尾空白：大小写不同的防伪码在合约内对应不同的 keccak 索引，
     * 不能按「忽略大小写」理解。
     *
     * @param fingerprint 电子防伪码
     * @return 命中的凭证；入参为空、空白或链上不存在时返回 {@link Optional#empty()}
     */
    Optional<ChainCertificate> findByFingerprint(String fingerprint);

    /**
     * 按认证编号查询存证。
     *
     * @param certId 认证编号（CR + 6 位序号）
     * @return 命中的凭证；入参空或不存在时返回空 Optional
     */
    Optional<ChainCertificate> findByCertId(String certId);

    /**
     * 按排放记录编号查询。
     *
     * 认证编号、排放记录编号、报告编号三表同号，因此与 {@link #findByCertId(String)} 结果等价。
     *
     * @param emissionId 排放记录编号
     * @return 命中的凭证；入参空或不存在时返回空 Optional
     */
    Optional<ChainCertificate> findByEmissionId(String emissionId);

    /**
     * 读取认证时写入链上的合规判定位掩码快照。
     *
     * 该快照只在上链（certify）时写入，之后不随排放数据或合规规则变化；
     * 与证书查询分开调用，避免每次普通证书查询都多付一次链调用。
     *
     * @param certId 认证编号；空或不存在时返回 0
     * @return 16 位位掩码（bit0 配额 / bit1 环比 / bit2 漏填 / bit3 因子 / bit4 迟报）
     */
    int complianceFlagsOf(String certId);

    /**
     * 链上已出现的最高认证序号（认证编号 "CR" 后的数字部分）。
     *
     * 编号按序号递增分配：新编号序号大于该值时，必然未在链上出现过，调用方可跳过逐号查询。
     * 返回值语义：
     *  - ≥ 0：当前逻辑合约给出的最高序号（0 表示链上尚无认证）；
     *  - -1：当前逻辑合约未提供该视图（如仍为 V1）或链读取失败，调用方必须回退到逐号检查。
     *
     * @return 最高认证序号，或 -1 表示不可用
     */
    long highestCertSeq();

    /**
     * 按交易哈希查询。
     *
     * 输入忽略大小写与 {@code 0x} 前缀；真实链需先定位交易（缓存或解析回执入参），
     * 定位失败时按「不存在」返回而非抛异常。
     *
     * @param txHash 交易哈希，可带或不带 {@code 0x} 前缀
     * @return 该笔交易对应的凭证；无法解析出认证编号或链上不存在时返回空 Optional
     */
    Optional<ChainCertificate> findByTxHash(String txHash);

    /**
     * 兼容旧语义：入参是电子防伪码（大小写敏感精确匹配）或交易哈希（忽略大小写与 0x 前缀）。
     *
     * 按输入形态优先尝试：64 位十六进制先走交易哈希，其余先走防伪码；未命中再尝试另一条路径。
     * 这样可避免「交易哈希先被当成防伪码查一次」的必然失败链调用。
     *
     * @param fingerprintOrTxHash 电子防伪码或交易哈希
     * @return 命中的凭证；两者都未命中时返回空 Optional
     */
    Optional<ChainCertificate> findAny(String fingerprintOrTxHash);

    /**
     * 按排放记录编号批量取链上凭证。
     *
     * 未命中的编号不会出现在返回 Map 中（不放入 null 值），调用方需以 {@code get} 是否为 null
     * 判断该记录是否有链上存证。返回顺序不保证与入参一致。
     *
     * @param emissionIds 排放记录编号集合，可为 null
     * @return 编号 → 凭证的映射，恒不为 null
     */
    Map<String, ChainCertificate> certificatesByEmissionIds(Collection<String> emissionIds);

    /**
     * 链上证书计数：历史累计、当前有效、已撤销、已作废四项。
     *
     * 四项由合约维护的计数器直接返回，是 {@link ChainIntegrityChecker} 对账的权威口径；
     * 注意「累计」不等于链上现存，因为已作废存证仍占计数。
     *
     * @return 计数快照，恒不为 null
     * @throws com.carbon.trace.common.ApiException 链不可达或返回值形状异常时抛出
     */
    ChainCertCount countCertificates();

    // ---------------- 链浏览 / 统计 ----------------

    /**
     * 最新区块高度。
     *
     * @return 当前链尖区块高度；无区块或返回值不可解析时返回 -1（不抛异常）
     */
    long latestBlockNumber();

    /**
     * 链上总交易数（节点口径，含非本业务交易，仅作展示参考）。
     *
     * @return 节点累计交易数；查询失败或字段缺失时返回 0
     */
    long chainTxCount();

    /**
     * 最近若干个区块（含认证交易明细）。
     *
     * 返回按区块高度倒序（最新在前）；{@code limit} 非正数时由实现回退到配置的默认值
     * （{@code carbon.chain.block-list-limit}）。单个区块解析失败会被跳过，不阻断整体浏览。
     *
     * @param limit 期望返回的区块数
     * @return 区块视图列表；链上无区块时返回空列表
     */
    List<ChainBlockView> listRecentBlocks(int limit);

    // ---------------- 合规判定（合约执行） ----------------

    /**
     * 一次调用完成全部链上规则判定。
     *
     * 入参中的「本期/上期合计、年累计、活动数据、因子」需由后端先行聚合，
     * 合约不具备访问业务库的能力；返回值中的 flags 为规则位掩码（见 {@code ComplianceResult.FLAG_*}）。
     *
     * @param input 判定输入，金额字段为十进制原值（网关负责定标）
     * @return 合约返回的判定结果，{@code onChain=true}
     * @throws com.carbon.trace.common.ApiException 链不可达或合约执行失败时抛出
     */
    ComplianceResult checkAll(ComplianceInput input);

    /**
     * 设置企业某年度配额（写链），作为配额规则的阈值来源。
     *
     * @param enterpriseId 企业编号
     * @param year         年份（如 2024）
     * @param quota        年度配额，按排放量口径（×100 定标），null 按 0 处理
     * @throws com.carbon.trace.common.ApiException 无写权限或链不可达时抛出
     */
    void setAnnualQuota(String enterpriseId, int year, BigDecimal quota);

    /**
     * 查询企业某年度配额。
     *
     * @param enterpriseId 企业编号
     * @param year         年份
     * @return 配额；未配置时返回 {@link BigDecimal#ZERO}，不返回 null
     */
    BigDecimal annualQuotaOf(String enterpriseId, int year);

    /**
     * 同步企业某报告期合计排放量到链上（写链），环比规则依赖上次报告期数据。
     *
     * @param enterpriseId 企业编号
     * @param period       报告期，格式 YYYY-MM
     * @param amount       该报告期排放量合计
     * @throws com.carbon.trace.common.ApiException 无写权限或链不可达时抛出
     */
    void syncPeriodTotal(String enterpriseId, String period, BigDecimal amount);

    /**
     * 同步企业某年度累计排放量到链上（写链），配额规则依赖该值。
     *
     * @param enterpriseId 企业编号
     * @param year         年份
     * @param amount       年度累计排放量
     * @throws com.carbon.trace.common.ApiException 无写权限或链不可达时抛出
     */
    void syncYearTotal(String enterpriseId, int year, BigDecimal amount);

    /**
     * 记录某企业某排放源最近一次使用的排放因子（写链），因子一致性规则依赖该值。
     *
     * @param enterpriseId 企业编号
     * @param sourceCode   排放源编码
     * @param factor       排放因子（×10000 定标存储）
     * @throws com.carbon.trace.common.ApiException 无写权限或链不可达时抛出
     */
    void rememberFactor(String enterpriseId, String sourceCode, BigDecimal factor);

    /**
     * 设置环比波动阈值（基点，1bp = 0.01%）。
     *
     * @param bp 阈值基点；合约要求大于 0 且不超过 10000，越界会 revert
     * @throws com.carbon.trace.common.ApiException 阈值非法、无写权限或链不可达时抛出
     */
    void setFluctuationThresholdBp(int bp);

    /**
     * 查询环比波动阈值。
     *
     * @return 阈值基点；链上不可读时返回 0
     */
    int fluctuationThresholdBp();

    /**
     * 设置迟报判定月数：报告期早于「当前月份 - months」即视为迟报。
     *
     * @param months 月数；合约要求不超过 24，越界会 revert
     * @throws com.carbon.trace.common.ApiException 月数非法、无写权限或链不可达时抛出
     */
    void setLateReportMonths(int months);

    /**
     * 查询迟报判定月数。
     *
     * @return 判定月数；链上不可读时返回 0
     */
    int lateReportMonths();

    // ---------------- 健康 ----------------

    /**
     * 链是否可用（供健康检查与前端提示）。
     *
     * stub 实现恒为 false；webase 实现带节流的探活（请求最新区块高度），
     * 探活失败不抛异常，只返回 false。
     *
     * @return true 表示最近一次探活成功
     */
    boolean available();

    /**
     * 运行模式标识，供业务层做分支判断（如跳过对账/迁移）。
     *
     * @return 固定小写字符串 {@code "webase"} 或 {@code "stub"}
     */
    String mode();
}
