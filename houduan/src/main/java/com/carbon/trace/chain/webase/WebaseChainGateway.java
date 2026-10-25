package com.carbon.trace.chain.webase;

import com.carbon.trace.chain.ChainGateway;
import com.carbon.trace.chain.config.ChainProperties;
import com.carbon.trace.chain.model.ChainBlockView;
import com.carbon.trace.chain.model.ChainCertCount;
import com.carbon.trace.chain.model.ChainCertStatus;
import com.carbon.trace.chain.model.ChainCertificate;
import com.carbon.trace.chain.model.ChainTxView;
import com.carbon.trace.chain.model.CertifyCommand;
import com.carbon.trace.chain.model.ComplianceInput;
import com.carbon.trace.chain.model.ComplianceResult;
import com.carbon.trace.chain.support.AmountCodec;
import com.carbon.trace.chain.support.Keccak256;
import com.carbon.trace.chain.webase.dto.TransHandleReceipt;
import com.carbon.trace.chain.webase.dto.TransHandleRequest;
import com.carbon.trace.chain.webase.dto.WebaseBlockDto;
import com.carbon.trace.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 真实链网关：通过 WeBASE-Front 的 REST 接口调用链上智能合约。
 *
 * 关键设计：
 *  - ABI 编解码交给 WeBASE（/trans/handle 按传入的 contractAbi 自动编解码），后端不引 FISCO BCOS SDK / web3j；
 *  - txHash / blockIndex 无法由合约自述，写交易时取回执、历史查询时按区块反查 CertCreated 事件，并做缓存；
 *  - 链上数据只追加不删除，删除业务记录对应 invalidateByEmissionId（作废标记）。
 *
 * 依赖：{@link WebaseClient}（HTTP）、{@link AbiLoader}（ABI）、{@link WebaseErrorMapper}（错误翻译）、
 * {@link ChainTxLocator}（交易位置缓存）与 {@link ChainProperties}（地址、群组、签名账户）。
 *
 * ABI 编解码规则（WeBASE 3.x，必须与合约参数顺序严格一致）：
 *  - 数值一律转成十进制字符串，uint256 传 long 即可；布尔传 true/false；
 *  - 字符串直接给原文、不能加引号（加引号会把引号写进链上数据）；
 *  - 数组/复杂类型序列化为 JSON 文本；
 *  - 只读方法的返回值是数组（按 ABI 输出顺序），个别版本对单返回值直接给裸值，需兼容。
 *
 * 金额缩放：排放量 ×100、排放因子 ×10000，全部由 {@link AmountCodec} 完成；本类不做二次缩放。
 *
 * 交易与区块定位：
 *  - 写成功用回执的 transactionHash + blockNumber 回填；
 *  - 按编号查证书时，先查 {@link ChainTxLocator} 缓存，未命中再按 createdBlock 拉区块、解析 input 找 certify；
 *  - 按交易哈希查证书时，先查缓存，再解析交易回执的 input 得到 certId。
 *
 * 可靠性与对账：
 *  - 本类不做自动重试（写交易重试可能产生重复上链），失败直接抛 {@link ApiException}；
 *    调用方需自行判断是否幂等后重试；
 *  - {@link #available()} 对探活结果做 30 秒缓存，避免健康检查把链打满；
 *  - 链上与业务库的差异不在这里修复，由 {@code ChainIntegrityChecker} 定时对账并告警。
 */
@Component
@ConditionalOnProperty(name = "carbon.chain.mode", havingValue = "webase")
public class WebaseChainGateway implements ChainGateway {

    private static final Logger log = LoggerFactory.getLogger(WebaseChainGateway.class);

    /** 认证编号形态：CR + 6 位序号（用于从事件解码结果中识别编号） */
    private static final Pattern CERT_ID_PATTERN = Pattern.compile("^CR\\d{6}$");

    private static final String HANDLE_LOCAL = "/trans/handle";
    private static final String HANDLE_WITH_SIGN = "/trans/handleWithSign";

    private final WebaseClient client;
    private final ChainProperties properties;
    private final AbiLoader abiLoader;
    private final WebaseErrorMapper errorMapper;
    private final ChainTxLocator txLocator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 探活结果缓存：时间戳与布尔值，配合 available() 节流对链的探活请求 */
    private volatile long availableCheckedAt = 0L;
    private volatile boolean availableCache = false;

    /** 存证合约 certify 函数选择器缓存（从 ABI 现算，见 certifySelector） */
    private volatile String certifySelectorCache;

    /**
     * 装配网关。
     *
     * 依赖全部由构造注入，本类无状态初始化逻辑（配置自检在 {@link #selfCheck()}），
     * 便于单测中用假实现替换 WebaseClient。
     *
     * @param client       WeBASE-Front REST 客户端
     * @param properties   链配置（合约地址、群组、签名账户、默认分页）
     * @param abiLoader    合约 ABI 装载器
     * @param errorMapper  错误映射器
     * @param txLocator    交易位置缓存
     */
    public WebaseChainGateway(WebaseClient client,
                              ChainProperties properties,
                              AbiLoader abiLoader,
                              WebaseErrorMapper errorMapper,
                              ChainTxLocator txLocator) {
        this.client = client;
        this.properties = properties;
        this.abiLoader = abiLoader;
        this.errorMapper = errorMapper;
        this.txLocator = txLocator;
    }

    /**
     * 配置自检：合约地址与签名账户缺失属于配置错误，直接快速失败；链暂时不可达则只告警，不阻断启动。
     *
     * 快速失败的两类配置：
     *  - 任一合约地址为空（未部署或未注入）；
     *  - signUserId 与 userAddress 同时为空（没有可用的签名账户）。
     *
     * 连通性检查放在最后且吞掉异常：WeBASE-Front 可能晚于本应用启动，此时不应让整个系统起不来。
     *
     * @throws IllegalStateException 合约地址或签名账户缺失时抛出，阻止应用在错误配置下提供服务
     */
    @PostConstruct
    public void selfCheck() {
        if (registryAddress().isBlank() || complianceAddress().isBlank()) {
            throw new IllegalStateException("carbon.chain.mode=webase，但合约地址未配置。"
                    + "请执行 houduan/contracts/deploy-contracts.ps1 部署合约，并配置 "
                    + "carbon.chain.contracts.registry.address 与 carbon.chain.contracts.compliance.address。");
        }
        if (signUserId().isBlank() && userAddress().isBlank()) {
            throw new IllegalStateException("carbon.chain.mode=webase，但链上签名账户未配置。"
                    + "请配置 carbon.chain.webase.sign-user-id（WeBASE-Sign 托管）或 "
                    + "carbon.chain.webase.user-address（本地私钥用户）。");
        }
        log.info("链网关已装配：WeBASE-Front = {}，groupId = {}，签名方式 = {}",
                properties.getWebase().getBaseUrl(), properties.getWebase().getGroupId(),
                signUserId().isBlank() ? "本地私钥用户 " + userAddress() : "WeBASE-Sign " + signUserId());
        try {
            long blockNumber = latestBlockNumber();
            log.info("链连通性检查通过：最新区块高度 = {}", blockNumber);
        } catch (RuntimeException e) {
            log.warn("链连通性检查失败（不影响启动，链上功能将在 WeBASE-Front 可用后恢复）：{}", e.getMessage());
        }
    }

    // ---------------- 认证存证（写） ----------------

    /**
     * 认证上链：把认证要素整成定标参数调存证合约 certify，成功后用回执回填 txHash/区块高度并写入缓存。
     *
     * 参数顺序必须与 CarbonCertRegistry.certify 一致：
     * (certId, enterpriseId, enterpriseName, period, amountScaled, fingerprint, auditor, complianceFlags)。
     * 其中 amount 需先定标为整数，complianceFlags 虽是 int 也要按 uint256 传 long，避免被当作有符号数。
     *
     * @param command 认证要素；字符串为空时传空串（合约负责拒绝），金额可为 null
     * @return 新建的凭证视图，{@code txHash} 与 {@code blockIndex} 来自交易回执
     * @throws com.carbon.trace.common.ApiException 合约 revert（防伪码重复、编号已撤销等）、
     *                                              回执失败或链不可达时抛出
     */
    @Override
    public ChainCertificate certify(CertifyCommand command) {
        List<Object> params = new ArrayList<>();
        params.add(nullToEmpty(command.getCertId()));
        params.add(nullToEmpty(command.getEnterpriseId()));
        params.add(nullToEmpty(command.getEnterpriseName()));
        params.add(nullToEmpty(command.getPeriod()));
        params.add(AmountCodec.toScaled(command.getAmount()));
        params.add(nullToEmpty(command.getFingerprint()));
        params.add(nullToEmpty(command.getAuditor()));
        params.add((long) command.getComplianceFlags());

        TransHandleReceipt receipt = callWrite(contractRegistry(), "certify", params);

        ChainCertificate cert = new ChainCertificate();
        cert.setExists(true);
        cert.setStatus(ChainCertStatus.VALID);
        cert.setCertId(command.getCertId());
        cert.setEmissionId(command.getCertId());
        cert.setEnterpriseId(command.getEnterpriseId());
        cert.setEnterpriseName(command.getEnterpriseName());
        cert.setPeriod(command.getPeriod());
        cert.setAmount(command.getAmount());
        cert.setFingerprint(command.getFingerprint());
        cert.setAuditor(command.getAuditor());
        cert.setComplianceFlags(command.getComplianceFlags());
        cert.setTxHash(receipt.getTransactionHash());
        long blockNumber = receipt.blockNumberLong();
        // -1 表示回执未给出可解析高度，此时保留 null，不能写成 0（0 是创世块的真实高度）
        cert.setBlockIndex(blockNumber < 0 ? null : blockNumber);
        cert.setCreatedBlock(cert.getBlockIndex());
        txLocator.remember(command.getCertId(), receipt.getTransactionHash(), cert.getBlockIndex());
        return cert;
    }

    /**
     * 监管撤销：调存证合约 revoke 把状态翻转为终局 REVOKED。
     *
     * @param certId 认证编号（不存在时合约 revert，由错误映射转为 404）
     * @param reason 撤销原因，null 转为空串
     * @throws com.carbon.trace.common.ApiException 存证不存在、状态不允许撤销、无写权限或链不可达时抛出
     */
    @Override
    public void revoke(String certId, String reason) {
        callWrite(contractRegistry(), "revoke", List.of(nullToEmpty(certId), nullToEmpty(reason)));
    }

    /**
     * 排放记录删除对应的作废标记：调 invalidateByEmissionId 写链（未认证过的记录合约返回 false 不 revert）。
     *
     * 返回值被忽略：合约对「无存证」与「已作废」都返回 false，业务上均视为成功。
     *
     * @param emissionId 排放记录编号（=认证编号）
     * @param reason     作废原因，null 转为空串
     * @throws com.carbon.trace.common.ApiException 无写权限或链不可达时抛出
     */
    @Override
    public void invalidateByEmissionId(String emissionId, String reason) {
        // 链上不可删除，这里是作废标记；未认证过的排放记录合约会直接返回 false（不 revert）
        callWrite(contractRegistry(), "invalidateByEmissionId", List.of(nullToEmpty(emissionId), nullToEmpty(reason)));
    }

    // ---------------- 认证存证（读 / 核验） ----------------

    /**
     * 按电子防伪码精确查询（大小写敏感，与合约内对防伪码做 keccak 索引的口径天然一致）。
     *
     * @param fingerprint 电子防伪码，入参只做 trim，不做大小写转换
     * @return 命中凭证；空白或不存在时返回空 Optional
     */
    @Override
    public Optional<ChainCertificate> findByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        // 防伪码大小写敏感：大小写不同在合约里对应不同的 keccak 索引，天然区分
        return readCertificate("getByFingerprint", List.of(fingerprint.trim()));
    }

    /**
     * 按认证编号查询存证。
     *
     * @param certId 认证编号
     * @return 命中凭证（已尽力回填 txHash/blockIndex）；空白或不存在时返回空 Optional
     */
    @Override
    public Optional<ChainCertificate> findByCertId(String certId) {
        if (certId == null || certId.isBlank()) {
            return Optional.empty();
        }
        return readCertificate("getByCertId", List.of(certId));
    }

    /**
     * 按排放记录编号查询（与认证编号同号，走合约的 getByEmissionId 索引）。
     *
     * @param emissionId 排放记录编号
     * @return 命中凭证；空白或不存在时返回空 Optional
     */
    @Override
    public Optional<ChainCertificate> findByEmissionId(String emissionId) {
        if (emissionId == null || emissionId.isBlank()) {
            return Optional.empty();
        }
        return readCertificate("getByEmissionId", List.of(emissionId));
    }

    /**
     * 按交易哈希核验：先命中本进程缓存，否则解析回执入参解出认证编号再查询并回填缓存。
     *
     * 回执查询直接使用调用方传入的原始哈希（带不带 0x 都可），但缓存键统一归一化。
     * 回执中 blockNumber < 0 视为交易不存在（未上链或节点尚未同步），按「不存在」处理。
     *
     * @param txHash 交易哈希，大小写与 0x 前缀不敏感
     * @return 该笔交易对应的凭证；无法定位或链上不存在时返回空 Optional
     * @throws com.carbon.trace.common.ApiException 链不可达或回执查询失败时抛出
     */
    @Override
    public Optional<ChainCertificate> findByTxHash(String txHash) {
        if (txHash == null || txHash.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeHash(txHash);
        // 1) 本进程写过的交易直接命中缓存
        Optional<String> cached = txLocator.certIdByTxHash(normalized);
        if (cached.isPresent()) {
            return findByCertId(cached.get());
        }
        // 2) 历史交易：解析回执里的调用入参（certify 的首个参数即认证编号）
        JsonNode receipt = client.get("/" + groupId() + "/web3/transactionReceipt/" + txHash.trim());
        long blockNumber = TransHandleReceipt.parseNumber(text(receipt, "blockNumber"));
        if (blockNumber < 0) {
            return Optional.empty();
        }
        String certId = certifyCertId(text(receipt, "input"));
        if (certId == null) {
            return Optional.empty();
        }
        txLocator.remember(certId, txHash, blockNumber);
        return findByCertId(certId);
    }

    /**
     * 兼容旧语义：先按防伪码后按交易哈希。
     *
     * 注意防伪码路径会把整个字符串原样当作防伪码查询（一次链调用），
     * 因此传入交易哈希时会先产生一次「必然未命中」的链查询，再走哈希路径。
     *
     * @param fingerprintOrTxHash 电子防伪码或交易哈希
     * @return 命中的凭证；两者均未命中时返回空 Optional
     */
    @Override
    public Optional<ChainCertificate> findAny(String fingerprintOrTxHash) {
        Optional<ChainCertificate> byFingerprint = findByFingerprint(fingerprintOrTxHash);
        return byFingerprint.isPresent() ? byFingerprint : findByTxHash(fingerprintOrTxHash);
    }

    /**
     * 按编号集合批量取凭证，并按需按区块反查交易哈希回填展示字段。
     *
     * 两条优化：
     *  - 先对「有 createdBlock 但缺 txHash」的证书按区块去重，再逐块拉取一次，
     *    避免同一区块内有 N 条证书时重复请求 N 次；
     *  - 结果使用 LinkedHashMap，保持入参遍历顺序（便于前端稳定展示）。
     *
     * 单条证书查询失败会直接抛出异常，不做逐条容错；列表页应控制批量规模。
     *
     * @param emissionIds 排放记录编号集合，可为 null/空
     * @return 编号 → 凭证映射，恒不为 null；未命中的编号不出现
     * @throws com.carbon.trace.common.ApiException 任一链查询失败时抛出
     */
    @Override
    public Map<String, ChainCertificate> certificatesByEmissionIds(Collection<String> emissionIds) {
        Map<String, ChainCertificate> result = new LinkedHashMap<>();
        if (emissionIds == null || emissionIds.isEmpty()) {
            return result;
        }
        for (String emissionId : emissionIds) {
            findByEmissionId(emissionId).ifPresent(cert -> result.put(emissionId, cert));
        }
        // 批量按区块去重反查交易哈希，避免逐条查链
        Set<Long> blocks = new LinkedHashSet<>();
        for (ChainCertificate cert : result.values()) {
            if (cert.getCreatedBlock() != null && cert.getTxHash() == null) {
                blocks.add(cert.getCreatedBlock());
            }
        }
        Map<String, String> txHashByCertId = new HashMap<>();
        for (Long blockNumber : blocks) {
            WebaseBlockDto block = fetchBlock(blockNumber);
            if (block != null) {
                txHashByCertId.putAll(certifyTxsInBlock(block));
            }
        }
        for (ChainCertificate cert : result.values()) {
            if (cert.getTxHash() == null) {
                fillTxInfo(cert, txHashByCertId.get(cert.getCertId()));
            }
        }
        return result;
    }

    /**
     * 链上证书计数 total/valid/revoked/invalid 四元组（供对账与统计）。
     *
     * 合约 count 返回四个 uint256，顺序固定为 total、valid、revoked、invalid；
     * 返回值缺失时按 0 处理（见 {@link #num(JsonNode, int)}）。
     *
     * @return 计数快照，恒不为 null
     * @throws com.carbon.trace.common.ApiException 链不可达或返回值形状异常时抛出
     */
    @Override
    public ChainCertCount countCertificates() {
        JsonNode values = callRead(contractRegistry(), "count", List.of());
        ChainCertCount count = new ChainCertCount();
        count.setTotal(num(values, 0));
        count.setValid(num(values, 1));
        count.setRevoked(num(values, 2));
        count.setInvalid(num(values, 3));
        return count;
    }

    // ---------------- 链浏览 / 统计 ----------------

    /**
     * 查询最新区块高度（无法解析时回到缺省 -1）。
     *
     * 该调用同时也是 {@link #available()} 的探活手段：能拿到高度即认为链可达。
     *
     * @return 最新区块高度；节点无区块或返回值不可解析时返回 -1
     * @throws com.carbon.trace.common.ApiException 链不可达时抛出（由 WebaseClient 统一映射）
     */
    @Override
    public long latestBlockNumber() {
        JsonNode response = client.get("/" + groupId() + "/web3/blockNumber");
        return nodeToLong(response);
    }

    /**
     * 链上总交易数（节点口径，兼容 1.5.x 的 txSum 与 3.x 的 transactionCount）。
     *
     * 该值是节点累计交易总数，包含部署合约、权限交易等非业务交易，不能与存证数直接对账。
     *
     * @return 节点累计交易数；字段缺失或不可解析时返回 0
     */
    @Override
    public long chainTxCount() {
        JsonNode response = client.get("/" + groupId() + "/web3/transaction-total");
        if (response == null) {
            return 0L;
        }
        // WeBASE 1.5.x 返回 {txSum}，3.x 返回 {transactionCount}
        JsonNode count = response.hasNonNull("txSum") ? response.get("txSum") : response.get("transactionCount");
        return count == null ? 0L : nodeToLong(count);
    }

    /**
     * 倒序取最近 limit 个区块及其认证交易明细；limit 非法时回退配置的默认每页数。
     *
     * 区间为 [max(0, latest-size+1), latest]：高度不足 size 时从 0 开始，避免算出负数高度。
     * 每个区块单独请求，单个区块解析失败只跳过该块；limit 越大链请求越多，前端需限制上限。
     *
     * @param limit 期望区块数，非正数时使用 {@code carbon.chain.block-list-limit}
     * @return 区块视图列表（最新在前）；链上无区块时返回空列表
     * @throws com.carbon.trace.common.ApiException 查询最新高度失败时抛出
     */
    @Override
    public List<ChainBlockView> listRecentBlocks(int limit) {
        int size = limit <= 0 ? properties.getBlockListLimit() : limit;
        long latest = latestBlockNumber();
        if (latest < 0) {
            return List.of();
        }
        long from = Math.max(0L, latest - size + 1);
        List<ChainBlockView> blocks = new ArrayList<>();
        // 从链尖向前取 size 个块：高度不足时 from 会被夹到 0，不会产生负数高度
        for (long number = latest; number >= from; number--) {
            WebaseBlockDto dto = fetchBlock(number);
            if (dto == null) {
                continue;
            }
            ChainBlockView view = new ChainBlockView();
            view.setIndex(dto.index());
            view.setHash(dto.getHash());
            view.setPreviousHash(dto.previousHash());
            view.setTimestamp(dto.timestampMillis());
            view.setMerkleRoot(dto.merkleRoot());
            view.setNonce(dto.nonceLong());
            view.setMiner(dto.sealerName());
            for (CertifyTx tx : certifyTxs(dto)) {
                ChainTxView txView = new ChainTxView();
                txView.setTxHash(tx.txHash());
                txView.setEmissionId(tx.certId());
                txView.setEnterpriseName(tx.enterpriseName());
                txView.setPeriod(tx.period());
                txView.setAmount(AmountCodec.fromScaled(tx.amountScaled()));
                txView.setFingerprint(tx.fingerprint());
                txView.setAuditor(tx.auditor());
                view.getTransactions().add(txView);
            }
            blocks.add(view);
        }
        return blocks;
    }

    /**
     * 区块内的一笔认证交易（字段全部来自交易入参，不再逐笔回链查询）。
     *
     * @param txHash         交易哈希
     * @param certId         认证编号（从 ABI 入参解出，已通过 CR+6 位格式校验）
     * @param enterpriseId   企业编号
     * @param enterpriseName 企业名称
     * @param period         报告期 YYYY-MM
     * @param amountScaled   链上定标排放量（×100）
     * @param fingerprint    电子防伪码
     * @param auditor        审核人
     */
    private record CertifyTx(String txHash, String certId, String enterpriseId, String enterpriseName,
                             String period, long amountScaled, String fingerprint, String auditor) {
    }

    /**
     * 按高度拉取并反序列化区块。
     *
     * @param number 区块高度
     * @return 区块 DTO；响应不是对象或反序列化失败时返回 null（调用方按「跳过该块」处理）
     */
    private WebaseBlockDto fetchBlock(long number) {
        JsonNode response = client.get("/" + groupId() + "/web3/blockByNumber/" + number);
        if (response == null || !response.isObject()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(response, WebaseBlockDto.class);
        } catch (Exception e) {
            log.warn("区块 {} 解析失败：{}", number, e.getMessage());
            return null;
        }
    }

    // ---------------- 合规判定 ----------------

    /**
     * 合规判定：把后端聚合好的入参定标后调合约 checkAll，返回值按返回元组顺序还原为结果对象。
     *
     * 入参与返回值的顺序都是与合约的硬契约：
     *  入参 (enterpriseId, year, sourceCode, currentTotal, previousTotal, yearToDate,
     *        activityData, factor, periodYyyymm, currentYyyymm)
     *  返回 (flags, deltaBp, previousFactor, quota, yearToDate, thresholdBp)
     * 其中金额/因子在入参侧定标、在返回侧还原；deltaBp 与 thresholdBp 是基点整数，不缩放。
     *
     * 本方法不设置 {@code notice}；链不可用时的降级与提示由业务层处理。
     *
     * @param input 判定输入，金额字段为十进制原值
     * @return 判定结果，{@code onChain=true}
     * @throws com.carbon.trace.common.ApiException 链不可达、合约 revert 或返回值形状异常时抛出
     */
    @Override
    public ComplianceResult checkAll(ComplianceInput input) {
        List<Object> params = new ArrayList<>();
        params.add(nullToEmpty(input.getEnterpriseId()));
        params.add((long) input.getYear());
        params.add(nullToEmpty(input.getSourceCode()));
        params.add(AmountCodec.toScaled(input.getCurrentTotal()));
        params.add(AmountCodec.toScaled(input.getPreviousTotal()));
        params.add(AmountCodec.toScaled(input.getYearToDate()));
        params.add(AmountCodec.toScaled(input.getActivityData()));
        params.add(AmountCodec.factorToScaled(input.getFactor()));
        params.add(input.getPeriodYyyymm());
        params.add(input.getCurrentYyyymm());

        JsonNode values = callRead(contractCompliance(), "checkAll", params);
        ComplianceResult result = new ComplianceResult();
        result.setOnChain(true);
        result.setFlags((int) num(values, 0));
        result.setDeltaBp(num(values, 1));
        result.setPreviousFactor(AmountCodec.factorFromScaled(num(values, 2)));
        result.setQuota(AmountCodec.fromScaled(num(values, 3)));
        result.setYearToDate(AmountCodec.fromScaled(num(values, 4)));
        result.setThresholdBp(num(values, 5));
        return result;
    }

    /**
     * 设置企业某年度配额（写链，配额规则阈值）。
     *
     * @param enterpriseId 企业编号
     * @param year         年份
     * @param quota        配额（排放量口径，网关按 ×100 定标），null 按 0 处理
     * @throws com.carbon.trace.common.ApiException 无写权限或链不可达时抛出
     */
    @Override
    public void setAnnualQuota(String enterpriseId, int year, BigDecimal quota) {
        callWrite(contractCompliance(), "setAnnualQuota",
                List.of(nullToEmpty(enterpriseId), (long) year, AmountCodec.toScaled(quota)));
    }

    /**
     * 查询企业某年度配额；未配置返回 0。
     *
     * @param enterpriseId 企业编号
     * @param year         年份
     * @return 配额（已从 ×100 定点还原），恒不为 null
     * @throws com.carbon.trace.common.ApiException 链不可达时抛出
     */
    @Override
    public BigDecimal annualQuotaOf(String enterpriseId, int year) {
        JsonNode values = callRead(contractCompliance(), "annualQuotaOf",
                List.of(nullToEmpty(enterpriseId), (long) year));
        return AmountCodec.fromScaled(num(values, 0));
    }

    /**
     * 同步企业某报告期合计排放量（写链，环比规则依赖上次报告期数据）。
     *
     * 合约函数名为 setPeriodTotal，与接口方法名不同，注意不要按接口名去 ABI 里找。
     *
     * @param enterpriseId 企业编号
     * @param period       报告期 YYYY-MM
     * @param amount       报告期排放量合计
     * @throws com.carbon.trace.common.ApiException 无写权限或链不可达时抛出
     */
    @Override
    public void syncPeriodTotal(String enterpriseId, String period, BigDecimal amount) {
        callWrite(contractCompliance(), "setPeriodTotal",
                List.of(nullToEmpty(enterpriseId), nullToEmpty(period), AmountCodec.toScaled(amount)));
    }

    /**
     * 同步企业某年度累计排放量（写链，配额规则依赖）。
     *
     * @param enterpriseId 企业编号
     * @param year         年份
     * @param amount       年度累计排放量
     * @throws com.carbon.trace.common.ApiException 无写权限或链不可达时抛出
     */
    @Override
    public void syncYearTotal(String enterpriseId, int year, BigDecimal amount) {
        callWrite(contractCompliance(), "setYearTotal",
                List.of(nullToEmpty(enterpriseId), (long) year, AmountCodec.toScaled(amount)));
    }

    /**
     * 记录某企业某排放源最近一次排放因子（写链，因子一致性规则依赖）。
     *
     * @param enterpriseId 企业编号
     * @param sourceCode   排放源编码
     * @param factor       排放因子（网关按 ×10000 定标）
     * @throws com.carbon.trace.common.ApiException 无写权限或链不可达时抛出
     */
    @Override
    public void rememberFactor(String enterpriseId, String sourceCode, BigDecimal factor) {
        callWrite(contractCompliance(), "rememberFactor",
                List.of(nullToEmpty(enterpriseId), nullToEmpty(sourceCode), AmountCodec.factorToScaled(factor)));
    }

    /**
     * 设置环比波动阈值（写链，基点）。
     *
     * @param bp 阈值基点；合约要求 0 &lt; bp &le; 10000，非法值会 revert 并映射为 400
     * @throws com.carbon.trace.common.ApiException 阈值非法、无写权限或链不可达时抛出
     */
    @Override
    public void setFluctuationThresholdBp(int bp) {
        callWrite(contractCompliance(), "setFluctuationThresholdBp", List.of((long) bp));
    }

    /**
     * 查询环比波动阈值（基点）。
     *
     * @return 阈值基点；链上不可读或返回值缺失时返回 0
     */
    @Override
    public int fluctuationThresholdBp() {
        JsonNode values = callRead(contractCompliance(), "fluctuationThresholdBp", List.of());
        return (int) num(values, 0);
    }

    /**
     * 设置迟报判定月数（写链）。
     *
     * @param months 月数；合约要求不超过 24，非法值会 revert 并映射为 400
     * @throws com.carbon.trace.common.ApiException 月数非法、无写权限或链不可达时抛出
     */
    @Override
    public void setLateReportMonths(int months) {
        callWrite(contractCompliance(), "setLateReportMonths", List.of((long) months));
    }

    /**
     * 查询迟报判定月数。
     *
     * @return 判定月数；链上不可读或返回值缺失时返回 0
     */
    @Override
    public int lateReportMonths() {
        JsonNode values = callRead(contractCompliance(), "lateReportMonths", List.of());
        return (int) num(values, 0);
    }

    // ---------------- 健康 ----------------

    /**
     * 探活：调 latestBlockNumber 判断链可达，结果 30 秒内缓存复用，避免每请求都打链。
     *
     * 缓存窗口内的返回值固定：链刚恢复/刚断开时最多有 30 秒的延迟感知，属可接受误差。
     * 探活失败不抛异常，只更新缓存并返回 false。
     *
     * @return true 表示最近一次探活成功
     */
    @Override
    public boolean available() {
        long now = System.currentTimeMillis();
        // 30 秒窗口内直接复用上次结果，避免健康检查/页面轮询把探活请求打满
        if (now - availableCheckedAt < 30_000L) {
            return availableCache;
        }
        boolean ok;
        try {
            latestBlockNumber();
            ok = true;
        } catch (RuntimeException e) {
            ok = false;
        }
        availableCache = ok;
        availableCheckedAt = now;
        return ok;
    }

    /**
     * 运行模式标识。
     *
     * @return 恒为 {@code "webase"}
     */
    @Override
    public String mode() {
        return "webase";
    }

    // ---------------- 内部实现 ----------------

    /**
     * 只读调用：WeBASE 返回按 ABI 解码后的返回值数组。
     *
     * 兼容两种形状：标准数组直接返回；个别版本对单返回值只读方法返回裸文本，包装成单元素数组，
     * 保证上层按「下标取值」的逻辑统一。其余形状一律视为网关异常（502），避免静默返回错误结果。
     *
     * @param contract 合约配置
     * @param funcName 函数名
     * @param params   已按合约参数顺序排列、尚未编码的入参
     * @return 返回值数组节点
     * @throws ApiException 返回值既不是数组也不是文本时抛出
     */
    private JsonNode callRead(ChainProperties.Contract contract, String funcName, List<Object> params) {
        JsonNode response = invoke(contract, funcName, params);
        if (response != null && response.isArray()) {
            return response;
        }
        if (response != null && response.isTextual()) {
            // 个别版本对单返回值只读方法直接返回裸值
            return objectMapper.createArrayNode().add(response.asText());
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY,
                "链上只读调用未返回可解析结果：" + funcName);
    }

    /**
     * 写调用：返回交易回执，并按回执判定交易是否真正成功。
     *
     * 注意「HTTP 200」不等于「交易成功」：合约 revert 时 WeBASE 仍可能返回 2xx + 失败回执，
     * 因此必须检查 {@link TransHandleReceipt#successful()}，失败时交由 errorMapper 翻译 revert 原因。
     *
     * @param contract 合约配置
     * @param funcName 函数名
     * @param params   入参
     * @return 成功的交易回执（含 transactionHash、blockNumber）
     * @throws ApiException 响应不是对象、回执解析失败或交易执行失败时抛出
     */
    private TransHandleReceipt callWrite(ChainProperties.Contract contract, String funcName, List<Object> params) {
        JsonNode response = invoke(contract, funcName, params);
        if (response == null || !response.isObject()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "链上写调用未返回交易回执：" + funcName);
        }
        TransHandleReceipt receipt;
        try {
            receipt = objectMapper.treeToValue(response, TransHandleReceipt.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "链上交易回执解析失败：" + funcName);
        }
        if (!receipt.successful()) {
            throw errorMapper.fromReceipt(receipt.getMessage(), receipt.getOutput());
        }
        return receipt;
    }

    /**
     * 组装调用请求：funcParam 经 encodeFuncParams 转换，签名方式二选一决定走 handleWithSign 还是 handle。
     *
     * signUserId 非空时优先走 Sign 托管；否则必须已配置 userAddress（启动自检已保证）。
     * ABI 每次调用都会经 AbiLoader 缓存加载，不会重复读文件。
     *
     * @param contract 合约配置（名称、地址、ABI 位置）
     * @param funcName 函数名
     * @param params   入参
     * @return WeBASE 原始响应节点（只读为数组、写为回执对象）
     * @throws ApiException 合约地址未配置、ABI 加载失败或 HTTP 调用失败时抛出
     */
    private JsonNode invoke(ChainProperties.Contract contract, String funcName, List<Object> params) {
        TransHandleRequest request = new TransHandleRequest();
        request.setGroupId(groupId());
        request.setContractName(contract.getName());
        request.setContractAddress(requireAddress(contract));
        request.setFuncName(funcName);
        request.setFuncParam(encodeFuncParams(params));
        request.setContractAbi(abiLoader.load(contract.getAbiLocation()));

        if (!signUserId().isBlank()) {
            // 优先 Sign 托管：私钥不出 WeBASE-Sign，生产推荐
            request.setSignUserId(signUserId());
            return client.post(HANDLE_WITH_SIGN, request);
        }
        // 未配置 signUserId 时退回本地私钥用户；启动自检已保证 userAddress 非空
        request.setUser(userAddress());
        return client.post(HANDLE_LOCAL, request);
    }

    /**
     * WeBASE 3.x 的 funcParam 是 List&lt;String&gt;，每个元素按「原文」参与 ABI 编码：
     *  - 数字/布尔 → 1000 / true；
     *  - 字符串 → 直接给内容，不能加引号（加引号会把引号一起写进链上数据）；
     *  - 数组 → JSON 数组文本，形如 ["0x..."]（元素必须是 JSON 字符串）。
     *
     * 易错点：
     *  - null 转成空串而不是 "null"，否则链上会真的写入字符串 null；
     *  - 只对 Number/Boolean/String 走 {@code String.valueOf}，其余类型才做 JSON 序列化，
     *    避免字符串被序列化成带引号的形式；
     *  - JSON 序列化失败时降级为 {@code String.valueOf}，保证请求仍可发出（失败原因由链上判定）。
     *
     * @param params 按合约参数顺序排列的入参
     * @return 与入参等长的字符串列表
     */
    private List<String> encodeFuncParams(List<Object> params) {
        List<String> encoded = new ArrayList<>(params.size());
        for (Object param : params) {
            if (param == null) {
                encoded.add("");
            } else if (param instanceof Number || param instanceof Boolean || param instanceof String) {
                encoded.add(String.valueOf(param));
            } else {
                try {
                    encoded.add(objectMapper.writeValueAsString(param));
                } catch (Exception e) {
                    encoded.add(String.valueOf(param));
                }
            }
        }
        return encoded;
    }

    /**
     * 读取证书：命中则回填 txHash / blockIndex。
     *
     * 回填分两级：先查本进程缓存；未命中且证书带 createdBlock 时，按该区块反查 certify 交易。
     * 反查失败（区块不可读或区块内找不到对应交易）时仍返回证书，只是 txHash 为空，
     * 不应因此把「证书存在」误判为「证书不存在」。
     *
     * @param funcName 合约只读函数名（getByCertId / getByEmissionId / getByFingerprint）
     * @param params   该函数的入参
     * @return 命中凭证；链上不存在时返回空 Optional
     * @throws ApiException 链不可达或返回值形状异常时抛出
     */
    private Optional<ChainCertificate> readCertificate(String funcName, List<Object> params) {
        JsonNode values = callRead(contractRegistry(), funcName, params);
        ChainCertificate cert = toCertificate(values);
        if (!cert.isExists()) {
            return Optional.empty();
        }
        String certId = cert.getCertId();
        Optional<ChainTxLocator.Entry> cached = txLocator.lookup(certId);
        if (cached.isPresent()) {
            cert.setTxHash(cached.get().txHash());
            cert.setBlockIndex(cached.get().blockNumber());
        } else if (cert.getCreatedBlock() != null) {
            // 缓存未命中：按首次登记区块反查 certify 交易；区块很大时这里会产生一次较重的链请求
            WebaseBlockDto block = fetchBlock(cert.getCreatedBlock());
            String txHash = block == null ? null : certifyTxsInBlock(block).get(certId);
            fillTxInfo(cert, txHash);
            txLocator.remember(certId, txHash, cert.getCreatedBlock());
        }
        return Optional.of(cert);
    }

    /**
     * 用已知交易哈希与已存的 createdBlock 回填凭证展示字段（缓存未命中时 txHash 可为空）。
     *
     * @param cert    待回填的凭证（原地修改）
     * @param txHash  交易哈希，可为 null
     */
    private void fillTxInfo(ChainCertificate cert, String txHash) {
        cert.setTxHash(txHash);
        cert.setBlockIndex(cert.getCreatedBlock());
    }

    /**
     * 区块内的认证交易。
     *
     * WeBASE 3.x 的 /event/eventLogs/list 是事件订阅 + 等待回调的实现，
     * 超时即返回「已收到的部分结果」，同一区间多次查询条数不一致，不能作为可靠检索手段；
     * 因此这里改为本地解析区块交易的 input：仅当目标是存证合约且选择器为 certify 时才算认证交易，
     * 展示字段也一并从入参解出，避免逐笔回链查询（单次合约只读调用约 2 秒）。
     *
     * 过滤条件中的合约地址比较使用归一化哈希（忽略大小写与 0x），避免因大小写差异漏判。
     *
     * @param block 区块 DTO
     * @return 认证交易列表，恒不为 null；非存证合约交易、非 certify 交易都会被跳过
     */
    private List<CertifyTx> certifyTxs(WebaseBlockDto block) {
        List<CertifyTx> result = new ArrayList<>();
        JsonNode transactions = block.getTransactions();
        if (transactions == null || !transactions.isArray()) {
            return result;
        }
        String expectedTo = normalizeHash(registryAddress());
        for (JsonNode tx : transactions) {
            // 只处理发往存证合约的交易；to 为空（合约创建交易）不可能是 certify
            if (tx == null || !tx.isObject() || !expectedTo.equals(normalizeHash(text(tx, "to")))) {
                continue;
            }
            CertifyTx certifyTx = decodeCertifyCall(text(tx, "hash"), text(tx, "input"));
            if (certifyTx != null) {
                result.add(certifyTx);
            }
        }
        return result;
    }

    /**
     * 区块内认证交易的 certId → txHash 映射。
     *
     * 同一证书只会有一次成功认证，因此 Map 不会出现键冲突；若区块内出现重复 certId，
     * 后写入的会覆盖先写入的（仅影响展示，不影响链上状态）。
     *
     * @param block 区块 DTO
     * @return certId → txHash 映射，恒不为 null
     */
    private Map<String, String> certifyTxsInBlock(WebaseBlockDto block) {
        Map<String, String> result = new HashMap<>();
        for (CertifyTx tx : certifyTxs(block)) {
            result.put(tx.certId(), tx.txHash());
        }
        return result;
    }

    /**
     * 解出 certify 调用的入参。参数顺序见 CarbonCertRegistry.certify：
     * (string certId, string enterpriseId, string enterpriseName, string period,
     *  uint256 amountScaled, string fingerprint, string auditor, uint256 complianceFlags)
     * 非 certify 交易返回 null。
     *
     * 判定步骤：
     *  1. 去掉 0x 前缀，长度不足 8 或前 8 位（4 字节）与选择器不一致 → 非目标函数；
     *  2. 参数区按 32 字节一个 slot 解析，string 所在 slot 存的是相对参数区起点的字节偏移；
     *  3. certId 必须匹配 CR+6 位格式，作为二次防线，避免把其他合约的同选择器调用误认成认证。
     *
     * @param txHash 交易哈希（用于构造返回记录，可为 null）
     * @param input  交易 input（0x + 选择器 + 参数区），可为 null
     * @return 解析出的认证交易；非 certify 调用或格式非法时返回 null
     */
    private CertifyTx decodeCertifyCall(String txHash, String input) {
        String selector = certifySelector();
        if (input == null || selector == null) {
            return null;
        }
        String hex = input.startsWith("0x") || input.startsWith("0X") ? input.substring(2) : input;
        // input = 4 字节选择器 + 参数区；选择器比较忽略大小写，避免 checksum 地址风格差异
        if (hex.length() < 8 || !hex.regionMatches(true, 0, selector, 0, 8)) {
            return null;
        }
        String args = hex.substring(8);
        String certId = decodeString(args, 0);
        // 认证编号格式是额外的安全校验：格式不符说明这次调用不是本业务的 certify
        if (certId == null || !CERT_ID_PATTERN.matcher(certId).matches()) {
            return null;
        }
        return new CertifyTx(txHash, certId, decodeString(args, 1), decodeString(args, 2),
                decodeString(args, 3), decodeUint(args, 4), decodeString(args, 5), decodeString(args, 6));
    }

    /**
     * 从 certify 调用的 input 中解出认证编号（历史交易按交易哈希核验时使用）。
     *
     * @param input 交易 input
     * @return 认证编号；非 certify 调用或解析失败时返回 null
     */
    private String certifyCertId(String input) {
        CertifyTx tx = decodeCertifyCall(null, input);
        return tx == null ? null : tx.certId();
    }

    /**
     * certify 函数选择器：从 ABI 现算，避免合约参数调整后与常量漂移。
     *
     * 遍历 ABI 找到 type=function 且 name=certify 的条目，把 inputs 的 type 依次拼成规范签名
     * （如 {@code certify(string,string,string,string,uint256,string,string,uint256)}），
     * 再取 keccak256 前 4 字节。
     *
     * 缓存使用 volatile 但非原子更新：并发首次调用可能重复计算，结果一致，属可接受的良性竞争。
     *
     * @return 8 位十六进制选择器；ABI 中找不到 certify 时返回 null
     */
    private String certifySelector() {
        if (certifySelectorCache != null) {
            return certifySelectorCache;
        }
        JsonNode abi = abiLoader.load(properties.getContracts().getRegistry().getAbiLocation());
        if (abi == null || !abi.isArray()) {
            return null;
        }
        for (JsonNode item : abi) {
            if (!"function".equals(text(item, "type")) || !"certify".equals(text(item, "name"))) {
                continue;
            }
            List<String> types = new ArrayList<>();
            for (JsonNode input : item.path("inputs")) {
                types.add(input.path("type").asText());
            }
            // 规范签名只由函数名与参数类型组成，不含参数名与空格
            certifySelectorCache = Keccak256.selector("certify(" + String.join(",", types) + ")");
            return certifySelectorCache;
        }
        return null;
    }

    /**
     * 解码 ABI 参数区中第 slot 个 string（该槽位是偏移量，指向「长度 + 内容」）。
     *
     * 字节与十六进制字符的换算：1 字节 = 2 个 hex 字符，因此所有字节位置都要乘 2。
     * 偏移量是相对参数区起点（不含选择器）的字节数，不是相对整个 input。
     * 边界检查覆盖两层：偏移量本身不能越过参数区；偏移 + 32 字节长度字 + 内容不能越界。
     * 长度上限 512 字符是防御性限制，正常业务字段远小于该值，超限视为非法数据。
     *
     * @param argsHex 参数区十六进制（不含选择器）
     * @param slot    参数序号（从 0 开始，每个 slot 占 32 字节）
     * @return 解码后的 UTF-8 字符串；越界、长度非法或数字解析失败时返回 null
     */
    private static String decodeString(String argsHex, int slot) {
        if (argsHex == null || argsHex.length() < (slot + 1) * 64) {
            return null;
        }
        try {
            // slot 槽位存的是 32 字节偏移量；此处只需低 32 位，用 int 解析即可
            int offset = Integer.parseInt(argsHex.substring(slot * 64, slot * 64 + 64), 16);
            if (offset < 0 || (long) (offset + 32) * 2 > argsHex.length()) {
                return null;
            }
            // 偏移处第一个 32 字节字是字符串长度（字节数）
            int length = Integer.parseInt(argsHex.substring(offset * 2, offset * 2 + 64), 16);
            if (length <= 0 || length > 512 || (long) (offset + 32 + length) * 2 > argsHex.length()) {
                return null;
            }
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                int start = (offset + 32 + i) * 2;
                bytes[i] = (byte) Integer.parseInt(argsHex.substring(start, start + 2), 16);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解码 ABI 参数区中第 slot 个 uint256（金额等数值都落在 long 范围内，取低 64 位）。
     *
     * 每个 slot 是 32 字节（64 个 hex 字符），低 8 字节位于该槽位的后 16 个字符
     * （偏移 +48），因此直接解析这 16 个字符即可；超出 long 的高位被忽略。
     * 这依赖「业务数值不超过 2^63-1」的既有约束，不适用于任意 uint256 解码。
     *
     * @param argsHex 参数区十六进制（不含选择器）
     * @param slot    参数序号
     * @return 解析出的 long 值；越界或格式非法时返回 0
     */
    private static long decodeUint(String argsHex, int slot) {
        if (argsHex == null || argsHex.length() < (slot + 1) * 64) {
            return 0L;
        }
        try {
            return Long.parseLong(argsHex.substring(slot * 64 + 48, slot * 64 + 64), 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 链上 12 元组 → 证书对象（顺序即对外契约，见 CarbonCertRegistry.getByCertId 注释）。
     *
     * 字段顺序：exists, status, createdBlock, createdAt, certId, enterpriseId, enterpriseName,
     * period, amountScaled, fingerprint, auditor, reason。
     *
     * 两个易错点：
     *  - 字符串返回值会被 WeBASE 二次 JSON 序列化，带外层引号，需要 {@link #unquote(String)} 还原；
     *  - exists=false 时合约可能仍返回占位字段，这里强制把状态归零为 NONE，避免把「不存在」展示成有效。
     *
     * @param values 合约返回的数组节点
     * @return 证书视图，恒不为 null
     */
    private ChainCertificate toCertificate(JsonNode values) {
        ChainCertificate cert = new ChainCertificate();
        cert.setExists(bool(values, 0));
        cert.setStatus(ChainCertStatus.fromCode((int) num(values, 1)));
        cert.setCreatedBlock(num(values, 2));
        cert.setCreatedAt(num(values, 3));
        cert.setCertId(unquote(text(values, 4)));
        cert.setEmissionId(unquote(text(values, 4)));
        cert.setEnterpriseId(unquote(text(values, 5)));
        cert.setEnterpriseName(unquote(text(values, 6)));
        cert.setPeriod(unquote(text(values, 7)));
        cert.setAmount(AmountCodec.fromScaled(num(values, 8)));
        cert.setFingerprint(unquote(text(values, 9)));
        cert.setAuditor(unquote(text(values, 10)));
        cert.setReason(unquote(text(values, 11)));
        // 链上不存在时强制归零状态：占位元组里的 status 字段不可信
        if (!cert.isExists()) {
            cert.setStatus(ChainCertStatus.NONE);
        }
        return cert;
    }

    /**
     * 存证合约配置（名称/地址/ABI 位置）。
     *
     * @return 配置对象，恒不为 null（默认值在 ChainProperties 中初始化）
     */
    private ChainProperties.Contract contractRegistry() {
        return properties.getContracts().getRegistry();
    }

    /**
     * 合规合约配置（名称/地址/ABI 位置）。
     *
     * @return 配置对象，恒不为 null
     */
    private ChainProperties.Contract contractCompliance() {
        return properties.getContracts().getCompliance();
    }

    /**
     * 取合约地址；未配置视为内部配置错误直接抛 500。
     *
     * 正常启动路径已由 {@link #selfCheck()} 拦截空地址；这里兜底运行期被改配置/反射置空的情况。
     *
     * @param contract 合约配置
     * @return 去空白后的合约地址
     * @throws ApiException 地址为空时抛出（500）
     */
    private String requireAddress(ChainProperties.Contract contract) {
        String address = contract.getAddress();
        if (address == null || address.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "合约地址未配置：" + contract.getName());
        }
        return address.trim();
    }

    /**
     * 存证合约地址，缺省返回空串供自检判断。
     *
     * @return 去空白后的地址；未配置时返回空串（不返回 null）
     */
    private String registryAddress() {
        String address = contractRegistry().getAddress();
        return address == null ? "" : address.trim();
    }

    /**
     * 合规合约地址，缺省返回空串供自检判断。
     *
     * @return 去空白后的地址；未配置时返回空串
     */
    private String complianceAddress() {
        String address = contractCompliance().getAddress();
        return address == null ? "" : address.trim();
    }

    /**
     * 群组标识：缺省回退 group0（WeBASE 3.x 默认首个群组）。
     *
     * @return 去空白后的群组标识，恒不为空串
     */
    private String groupId() {
        String value = properties.getWebase().getGroupId();
        return value == null || value.isBlank() ? "group0" : value.trim();
    }

    /**
     * Sign 托管用户标识（空串表示未配置，转为本地私钥模式）。
     *
     * @return 去空白后的 signUserId；未配置时返回空串
     */
    private String signUserId() {
        String value = properties.getWebase().getSignUserId();
        return value == null ? "" : value.trim();
    }

    /**
     * 本地私钥用户地址（空串表示未配置）。
     *
     * @return 去空白后的用户地址；未配置时返回空串
     */
    private String userAddress() {
        String value = properties.getWebase().getUserAddress();
        return value == null ? "" : value.trim();
    }

    /**
     * null 归一为空串：避免把 null 字符串写进链上编码。
     *
     * @param value 原始字符串，可为 null
     * @return 原值或空串
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * WeBASE 3.x 把每个返回值先序列化成 JSON 文本再放进数组，
     * 字符串因此会多一层引号（"\"CR000001\""），取出时统一还原为原始内容。
     *
     * 只处理以引号开头的值；解析失败时原样返回，避免因个别脏数据丢掉整个字段。
     *
     * @param value 合约返回的文本值，可为 null
     * @return 去掉外层 JSON 引号后的内容
     */
    private String unquote(String value) {
        if (value == null || value.length() < 2 || !value.startsWith("\"")) {
            return value;
        }
        try {
            return objectMapper.readValue(value, String.class);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 哈希归一化：去空白、转小写、去 0x 前缀，用于交易归属比对与缓存键。
     *
     * @param value 原始哈希，可为 null
     * @return 归一化后的哈希；null 返回空串
     */
    private static String normalizeHash(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim().toLowerCase();
        return v.startsWith("0x") ? v.substring(2) : v;
    }

    /**
     * 取 JSON 对象指定字段的文本值；节点/字段缺失或为 null 返回 null。
     *
     * 注意这里用 asText，因此数值字段会转成十进制文本（不经 parseNumber）。
     *
     * @param node  JSON 对象，可为 null
     * @param field 字段名
     * @return 字段文本；缺失或 null 时返回 null
     */
    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * 取 JSON 数组指定下标的文本值；越界或为 null 返回 null。
     *
     * @param array JSON 数组，可为 null
     * @param index 下标
     * @return 元素文本；越界或 null 时返回 null
     */
    private static String text(JsonNode array, int index) {
        JsonNode value = array == null ? null : array.get(index);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * 取 JSON 数组指定下标的布尔值；缺失默认为 false。
     *
     * 文本 "true"/"false" 也会被 Jackson 正确识别，无需额外转换。
     *
     * @param array JSON 数组，可为 null
     * @param index 下标
     * @return 布尔值；越界或缺失时返回 false
     */
    private static boolean bool(JsonNode array, int index) {
        JsonNode value = array == null ? null : array.get(index);
        return value != null && value.asBoolean(false);
    }

    /**
     * 取 JSON 数组指定下标的数值；缺失返回 0。
     *
     * @param array JSON 数组，可为 null
     * @param index 下标
     * @return 数值；越界或缺失时返回 0
     */
    private static long num(JsonNode array, int index) {
        JsonNode value = array == null ? null : array.get(index);
        return nodeToLong(value);
    }

    /**
     * JsonNode → long：数字节点直接取值，文本走十进制/十六进制解析，缺失返回 0。
     *
     * 注意与 {@link TransHandleReceipt#parseNumber(String)} 的差异：那里解析失败返回 -1（区分「缺失」），
     * 这里统一返回 0（用于「没取到就当 0」的计数/金额场景）。区块高度等需要区分缺失的字段
     * 不应走本方法。
     *
     * @param value JSON 节点，可为 null
     * @return 数值；缺失或无法解析时返回 0
     */
    private static long nodeToLong(JsonNode value) {
        if (value == null || value.isNull()) {
            return 0L;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        return TransHandleReceipt.parseNumber(value.asText());
    }
}
