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
import com.carbon.trace.chain.webase.dto.DecodedEventLogDto;
import com.carbon.trace.chain.webase.dto.EventLogListRequest;
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
        // 2) 历史交易：按交易回执定位区块，再反查该区块的 CertCreated 事件
        JsonNode receipt = client.get("/" + groupId() + "/web3/transactionReceipt/" + txHash.trim());
        long blockNumber = TransHandleReceipt.parseNumber(text(receipt, "blockNumber"));
        if (blockNumber < 0) {
            return Optional.empty();
        }
        String certId = eventsInRange(blockNumber, blockNumber).stream()
                .filter(e -> normalized.equals(normalizeHash(e.transactionHash())))
                .map(this::extractCertId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
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
        // 批量按区块去重反查交易哈希，避免逐条查事件
        Set<Long> blocks = new LinkedHashSet<>();
        for (ChainCertificate cert : result.values()) {
            if (cert.getCreatedBlock() != null && cert.getTxHash() == null) {
                blocks.add(cert.getCreatedBlock());
            }
        }
        Map<String, String> txHashByCertId = new HashMap<>();
        for (Long block : blocks) {
            for (DecodedEventLogDto event : eventsInRange(block, block)) {
                String certId = extractCertId(event);
                if (certId != null) {
                    txHashByCertId.put(certId, event.transactionHash());
                }
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
        JsonNode txSum = response == null ? null : response.get("txSum");
        return txSum == null ? 0L : nodeToLong(txSum);
    }

    @Override
    public List<ChainBlockView> listRecentBlocks(int limit) {
        int size = limit <= 0 ? properties.getBlockListLimit() : limit;
        long latest = latestBlockNumber();
        if (latest < 0) {
            return List.of();
        }
        long from = Math.max(0L, latest - size + 1);
        List<DecodedEventLogDto> events = eventsInRange(from, latest);
        Map<String, String> certIdByTxHash = new HashMap<>();
        for (DecodedEventLogDto event : events) {
            String certId = extractCertId(event);
            if (certId != null && event.transactionHash() != null) {
                certIdByTxHash.put(normalizeHash(event.transactionHash()), certId);
            }
        }

        List<ChainBlockView> blocks = new ArrayList<>();
        for (long number = latest; number >= from; number--) {
            WebaseBlockDto dto = fetchBlock(number);
            if (dto == null) {
                continue;
            }
            ChainBlockView view = new ChainBlockView();
            view.setIndex(dto.index());
            view.setHash(dto.getHash());
            view.setPreviousHash(dto.getParentHash());
            view.setTimestamp(dto.timestampMillis());
            view.setMerkleRoot(dto.merkleRoot());
            view.setNonce(dto.nonceLong());
            view.setMiner(dto.getSealer());
            for (String txHash : dto.transactionHashes()) {
                String certId = certIdByTxHash.get(normalizeHash(txHash));
                if (certId == null) {
                    // 非认证交易（如合规镜像写入）不展示，保持区块浏览语义与认证存证一致
                    continue;
                }
                ChainTxView tx = new ChainTxView();
                tx.setTxHash(txHash);
                tx.setEmissionId(certId);
                fillTxDetail(tx, certId);
                view.getTransactions().add(tx);
            }
            blocks.add(view);
        }
        return blocks;
    }

    private void fillTxDetail(ChainTxView tx, String certId) {
        findByCertId(certId).ifPresent(cert -> {
            tx.setEnterpriseName(cert.getEnterpriseName());
            tx.setPeriod(cert.getPeriod());
            tx.setAmount(cert.getAmount());
            tx.setFingerprint(cert.getFingerprint());
            tx.setAuditor(cert.getAuditor());
        });
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
        request.setFuncParam(params);
        request.setContractAbi(abiLoader.load(contract.getAbiLocation()));

        if (!signUserId().isBlank()) {
            request.setSignUserId(signUserId());
            return client.post(HANDLE_WITH_SIGN, request);
        }
        request.setUser(userAddress());
        return client.post(HANDLE_LOCAL, request);
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
            String txHash = eventsInRange(cert.getCreatedBlock(), cert.getCreatedBlock()).stream()
                    .filter(e -> certId.equals(extractCertId(e)))
                    .map(DecodedEventLogDto::transactionHash)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            fillTxInfo(cert, txHash);
            txLocator.remember(certId, txHash, cert.getCreatedBlock());
        }
        return Optional.of(cert);
    }

    private void fillTxInfo(ChainCertificate cert, String txHash) {
        cert.setTxHash(txHash);
        cert.setBlockIndex(cert.getCreatedBlock());
    }

    /** 事件日志查询（按区块区间），WeBASE 会按 ABI 解码 */
    private List<DecodedEventLogDto> eventsInRange(long fromBlock, long toBlock) {
        EventLogListRequest request = new EventLogListRequest();
        request.setGroupId(groupId());
        request.setContractAbi(abiLoader.load(properties.getContracts().getRegistry().getAbiLocation()));
        request.setContractAddress(registryAddress());
        request.setFromBlock(fromBlock);
        request.setToBlock(toBlock);
        try {
            JsonNode response = client.post("/event/eventLogs/list", request);
            JsonNode data = response == null ? null : response.get("data");
            if (data == null || !data.isArray()) {
                return List.of();
            }
            List<DecodedEventLogDto> result = new ArrayList<>();
            for (JsonNode item : data) {
                result.add(objectMapper.treeToValue(item, DecodedEventLogDto.class));
            }
            return result;
        } catch (Exception e) {
            // 事件反查失败不影响核验主流程，退化为「无交易哈希」
            log.warn("链上事件日志查询失败（区块 {}~{}）：{}", fromBlock, toBlock, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从解码后的事件参数中识别认证编号。
     * indexed 参数在各版本中的排布位置不一致，因此在全部参数中按编号形态匹配，保证兼容。
     */
    private String extractCertId(DecodedEventLogDto event) {
        JsonNode data = event.getData();
        if (data == null || !data.isArray()) {
            return null;
        }
        for (JsonNode node : data) {
            if (node != null && node.isTextual() && CERT_ID_PATTERN.matcher(node.asText()).matches()) {
                return node.asText();
            }
        }
        return null;
    }

    /** 链上 12 元组 → 证书对象（顺序即对外契约，见 CarbonCertRegistry.getByCertId 注释） */
    private ChainCertificate toCertificate(JsonNode values) {
        ChainCertificate cert = new ChainCertificate();
        cert.setExists(bool(values, 0));
        cert.setStatus(ChainCertStatus.fromCode((int) num(values, 1)));
        cert.setCreatedBlock(num(values, 2));
        cert.setCreatedAt(num(values, 3));
        cert.setCertId(text(values, 4));
        cert.setEmissionId(text(values, 4));
        cert.setEnterpriseId(text(values, 5));
        cert.setEnterpriseName(text(values, 6));
        cert.setPeriod(text(values, 7));
        cert.setAmount(AmountCodec.fromScaled(num(values, 8)));
        cert.setFingerprint(text(values, 9));
        cert.setAuditor(text(values, 10));
        cert.setReason(text(values, 11));
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

    private int groupId() {
        return properties.getWebase().getGroupId();
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
