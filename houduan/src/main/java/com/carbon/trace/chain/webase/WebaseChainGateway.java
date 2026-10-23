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

    private volatile long availableCheckedAt = 0L;
    private volatile boolean availableCache = false;

    /** 存证合约 certify 函数选择器缓存（从 ABI 现算，见 certifySelector） */
    private volatile String certifySelectorCache;

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

    /** 配置自检：合约地址与签名账户缺失属于配置错误，直接快速失败；链暂时不可达则只告警，不阻断启动 */
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
        cert.setBlockIndex(blockNumber < 0 ? null : blockNumber);
        cert.setCreatedBlock(cert.getBlockIndex());
        txLocator.remember(command.getCertId(), receipt.getTransactionHash(), cert.getBlockIndex());
        return cert;
    }

    @Override
    public void revoke(String certId, String reason) {
        callWrite(contractRegistry(), "revoke", List.of(nullToEmpty(certId), nullToEmpty(reason)));
    }

    @Override
    public void invalidateByEmissionId(String emissionId, String reason) {
        // 链上不可删除，这里是作废标记；未认证过的排放记录合约会直接返回 false（不 revert）
        callWrite(contractRegistry(), "invalidateByEmissionId", List.of(nullToEmpty(emissionId), nullToEmpty(reason)));
    }

    // ---------------- 认证存证（读 / 核验） ----------------

    @Override
    public Optional<ChainCertificate> findByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        // 防伪码大小写敏感：大小写不同在合约里对应不同的 keccak 索引，天然区分
        return readCertificate("getByFingerprint", List.of(fingerprint.trim()));
    }

    @Override
    public Optional<ChainCertificate> findByCertId(String certId) {
        if (certId == null || certId.isBlank()) {
            return Optional.empty();
        }
        return readCertificate("getByCertId", List.of(certId));
    }

    @Override
    public Optional<ChainCertificate> findByEmissionId(String emissionId) {
        if (emissionId == null || emissionId.isBlank()) {
            return Optional.empty();
        }
        return readCertificate("getByEmissionId", List.of(emissionId));
    }

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

    @Override
    public Optional<ChainCertificate> findAny(String fingerprintOrTxHash) {
        Optional<ChainCertificate> byFingerprint = findByFingerprint(fingerprintOrTxHash);
        return byFingerprint.isPresent() ? byFingerprint : findByTxHash(fingerprintOrTxHash);
    }

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

    @Override
    public long latestBlockNumber() {
        JsonNode response = client.get("/" + groupId() + "/web3/blockNumber");
        return nodeToLong(response);
    }

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

    @Override
    public List<ChainBlockView> listRecentBlocks(int limit) {
        int size = limit <= 0 ? properties.getBlockListLimit() : limit;
        long latest = latestBlockNumber();
        if (latest < 0) {
            return List.of();
        }
        long from = Math.max(0L, latest - size + 1);
        List<ChainBlockView> blocks = new ArrayList<>();
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

    /** 区块内的一笔认证交易（字段全部来自交易入参，不再逐笔回链查询） */
    private record CertifyTx(String txHash, String certId, String enterpriseId, String enterpriseName,
                             String period, long amountScaled, String fingerprint, String auditor) {
    }

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

    @Override
    public void setAnnualQuota(String enterpriseId, int year, BigDecimal quota) {
        callWrite(contractCompliance(), "setAnnualQuota",
                List.of(nullToEmpty(enterpriseId), (long) year, AmountCodec.toScaled(quota)));
    }

    @Override
    public BigDecimal annualQuotaOf(String enterpriseId, int year) {
        JsonNode values = callRead(contractCompliance(), "annualQuotaOf",
                List.of(nullToEmpty(enterpriseId), (long) year));
        return AmountCodec.fromScaled(num(values, 0));
    }

    @Override
    public void syncPeriodTotal(String enterpriseId, String period, BigDecimal amount) {
        callWrite(contractCompliance(), "setPeriodTotal",
                List.of(nullToEmpty(enterpriseId), nullToEmpty(period), AmountCodec.toScaled(amount)));
    }

    @Override
    public void syncYearTotal(String enterpriseId, int year, BigDecimal amount) {
        callWrite(contractCompliance(), "setYearTotal",
                List.of(nullToEmpty(enterpriseId), (long) year, AmountCodec.toScaled(amount)));
    }

    @Override
    public void rememberFactor(String enterpriseId, String sourceCode, BigDecimal factor) {
        callWrite(contractCompliance(), "rememberFactor",
                List.of(nullToEmpty(enterpriseId), nullToEmpty(sourceCode), AmountCodec.factorToScaled(factor)));
    }

    @Override
    public void setFluctuationThresholdBp(int bp) {
        callWrite(contractCompliance(), "setFluctuationThresholdBp", List.of((long) bp));
    }

    @Override
    public int fluctuationThresholdBp() {
        JsonNode values = callRead(contractCompliance(), "fluctuationThresholdBp", List.of());
        return (int) num(values, 0);
    }

    @Override
    public void setLateReportMonths(int months) {
        callWrite(contractCompliance(), "setLateReportMonths", List.of((long) months));
    }

    @Override
    public int lateReportMonths() {
        JsonNode values = callRead(contractCompliance(), "lateReportMonths", List.of());
        return (int) num(values, 0);
    }

    // ---------------- 健康 ----------------

    @Override
    public boolean available() {
        long now = System.currentTimeMillis();
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

    @Override
    public String mode() {
        return "webase";
    }

    // ---------------- 内部实现 ----------------

    /** 只读调用：WeBASE 返回按 ABI 解码后的返回值数组 */
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

    /** 写调用：返回交易回执 */
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

    private JsonNode invoke(ChainProperties.Contract contract, String funcName, List<Object> params) {
        TransHandleRequest request = new TransHandleRequest();
        request.setGroupId(groupId());
        request.setContractName(contract.getName());
        request.setContractAddress(requireAddress(contract));
        request.setFuncName(funcName);
        request.setFuncParam(encodeFuncParams(params));
        request.setContractAbi(abiLoader.load(contract.getAbiLocation()));

        if (!signUserId().isBlank()) {
            request.setSignUserId(signUserId());
            return client.post(HANDLE_WITH_SIGN, request);
        }
        request.setUser(userAddress());
        return client.post(HANDLE_LOCAL, request);
    }

    /**
     * WeBASE 3.x 的 funcParam 是 List&lt;String&gt;，每个元素按「原文」参与 ABI 编码：
     *  - 数字/布尔 → 1000 / true；
     *  - 字符串 → 直接给内容，不能加引号（加引号会把引号一起写进链上数据）；
     *  - 数组 → JSON 数组文本，形如 ["0x..."]（元素必须是 JSON 字符串）。
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

    /** 读取证书：命中则回填 txHash / blockIndex */
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
            WebaseBlockDto block = fetchBlock(cert.getCreatedBlock());
            String txHash = block == null ? null : certifyTxsInBlock(block).get(certId);
            fillTxInfo(cert, txHash);
            txLocator.remember(certId, txHash, cert.getCreatedBlock());
        }
        return Optional.of(cert);
    }

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
     */
    private List<CertifyTx> certifyTxs(WebaseBlockDto block) {
        List<CertifyTx> result = new ArrayList<>();
        JsonNode transactions = block.getTransactions();
        if (transactions == null || !transactions.isArray()) {
            return result;
        }
        String expectedTo = normalizeHash(registryAddress());
        for (JsonNode tx : transactions) {
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

    /** 区块内认证交易的 certId → txHash 映射 */
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
     */
    private CertifyTx decodeCertifyCall(String txHash, String input) {
        String selector = certifySelector();
        if (input == null || selector == null) {
            return null;
        }
        String hex = input.startsWith("0x") || input.startsWith("0X") ? input.substring(2) : input;
        if (hex.length() < 8 || !hex.regionMatches(true, 0, selector, 0, 8)) {
            return null;
        }
        String args = hex.substring(8);
        String certId = decodeString(args, 0);
        if (certId == null || !CERT_ID_PATTERN.matcher(certId).matches()) {
            return null;
        }
        return new CertifyTx(txHash, certId, decodeString(args, 1), decodeString(args, 2),
                decodeString(args, 3), decodeUint(args, 4), decodeString(args, 5), decodeString(args, 6));
    }

    /** 从 certify 调用的 input 中解出认证编号（历史交易按交易哈希核验时使用） */
    private String certifyCertId(String input) {
        CertifyTx tx = decodeCertifyCall(null, input);
        return tx == null ? null : tx.certId();
    }

    /** certify 函数选择器：从 ABI 现算，避免合约参数调整后与常量漂移 */
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
            certifySelectorCache = Keccak256.selector("certify(" + String.join(",", types) + ")");
            return certifySelectorCache;
        }
        return null;
    }

    /** 解码 ABI 参数区中第 slot 个 string（该槽位是偏移量，指向 长度+内容） */
    private static String decodeString(String argsHex, int slot) {
        if (argsHex == null || argsHex.length() < (slot + 1) * 64) {
            return null;
        }
        try {
            int offset = Integer.parseInt(argsHex.substring(slot * 64, slot * 64 + 64), 16);
            if (offset < 0 || (long) (offset + 32) * 2 > argsHex.length()) {
                return null;
            }
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

    /** 解码 ABI 参数区中第 slot 个 uint256（金额等数值都落在 long 范围内，取低 64 位） */
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

    /** 链上 12 元组 → 证书对象（顺序即对外契约，见 CarbonCertRegistry.getByCertId 注释） */
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
        if (!cert.isExists()) {
            cert.setStatus(ChainCertStatus.NONE);
        }
        return cert;
    }

    private ChainProperties.Contract contractRegistry() {
        return properties.getContracts().getRegistry();
    }

    private ChainProperties.Contract contractCompliance() {
        return properties.getContracts().getCompliance();
    }

    private String requireAddress(ChainProperties.Contract contract) {
        String address = contract.getAddress();
        if (address == null || address.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "合约地址未配置：" + contract.getName());
        }
        return address.trim();
    }

    private String registryAddress() {
        String address = contractRegistry().getAddress();
        return address == null ? "" : address.trim();
    }

    private String complianceAddress() {
        String address = contractCompliance().getAddress();
        return address == null ? "" : address.trim();
    }

    private String groupId() {
        String value = properties.getWebase().getGroupId();
        return value == null || value.isBlank() ? "group0" : value.trim();
    }

    private String signUserId() {
        String value = properties.getWebase().getSignUserId();
        return value == null ? "" : value.trim();
    }

    private String userAddress() {
        String value = properties.getWebase().getUserAddress();
        return value == null ? "" : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * WeBASE 3.x 把每个返回值先序列化成 JSON 文本再放进数组，
     * 字符串因此会多一层引号（"\"CR000001\""），取出时统一还原为原始内容。
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

    private static String normalizeHash(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim().toLowerCase();
        return v.startsWith("0x") ? v.substring(2) : v;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String text(JsonNode array, int index) {
        JsonNode value = array == null ? null : array.get(index);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean bool(JsonNode array, int index) {
        JsonNode value = array == null ? null : array.get(index);
        return value != null && value.asBoolean(false);
    }

    private static long num(JsonNode array, int index) {
        JsonNode value = array == null ? null : array.get(index);
        return nodeToLong(value);
    }

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
