package com.carbon.trace.service.impl;

import com.carbon.trace.chain.ChainGateway;
import com.carbon.trace.chain.config.ChainProperties;
import com.carbon.trace.chain.model.ChainBlockView;
import com.carbon.trace.chain.model.ChainCertCount;
import com.carbon.trace.chain.model.ChainStats;
import com.carbon.trace.chain.model.ChainTxView;
import com.carbon.trace.service.BlockchainService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区块链服务实现：全部数据来自链网关（真实链或离线桩）。
 * 本类只是把链网关返回的结构型数据组装成前端友好的 Map 结构，不持久化、不写链；
 * 链不可达时统计接口降级为 0，健康接口捕获异常并在 notice 中透出原因，避免整体 500。
 */
@Service
public class BlockchainServiceImpl implements BlockchainService {

    private final ChainGateway chainGateway;
    private final ChainProperties properties;

    public BlockchainServiceImpl(ChainGateway chainGateway, ChainProperties properties) {
        this.chainGateway = chainGateway;
        this.properties = properties;
    }

    @Override
    public List<Map<String, Object>> listBlocks(int limit) {
        int size = limit <= 0 ? properties.getBlockListLimit() : limit;
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChainBlockView block : chainGateway.listRecentBlocks(size)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", block.getIndex());
            item.put("hash", block.getHash());
            item.put("previousHash", block.getPreviousHash());
            item.put("timestamp", block.getTimestamp());
            item.put("merkleRoot", block.getMerkleRoot());
            item.put("nonce", block.getNonce());
            item.put("miner", block.getMiner());

            List<Map<String, Object>> txList = new ArrayList<>();
            if (block.getTransactions() != null) {
                for (ChainTxView tx : block.getTransactions()) {
                    Map<String, Object> txItem = new LinkedHashMap<>();
                    txItem.put("txHash", tx.getTxHash());
                    txItem.put("emissionId", tx.getEmissionId());
                    txItem.put("enterpriseName", tx.getEnterpriseName());
                    txItem.put("period", tx.getPeriod());
                    txItem.put("amount", tx.getAmount());
                    txItem.put("fingerprint", tx.getFingerprint());
                    txItem.put("auditor", tx.getAuditor());
                    txList.add(txItem);
                }
            }
            item.put("transactions", txList);
            result.add(item);
        }
        return result;
    }

    @Override
    public Map<String, Object> stats() {
        ChainStats stats = collect();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("blockCount", stats.getBlockCount());
        result.put("txCount", stats.getTxCount());
        result.put("activeCount", stats.getActiveCount());
        result.put("revokedCount", stats.getRevokedCount());
        result.put("invalidCount", stats.getInvalidCount());
        result.put("mode", stats.getMode());
        result.put("chainAvailable", stats.isChainAvailable());
        return result;
    }

    @Override
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", chainGateway.mode());
        result.put("available", chainGateway.available());
        result.put("groupId", properties.getWebase().getGroupId());
        result.put("registryAddress", properties.getContracts().getRegistry().getAddress());
        result.put("complianceAddress", properties.getContracts().getCompliance().getAddress());
        result.put("signMode", properties.getWebase().getSignUserId() == null
                || properties.getWebase().getSignUserId().isBlank() ? "user-address" : "sign-user-id");
        try {
            long latest = chainGateway.latestBlockNumber();
            result.put("latestBlockNumber", latest);
            result.put("chainTxCount", chainGateway.chainTxCount());
        } catch (RuntimeException e) {
            result.put("latestBlockNumber", null);
            result.put("chainTxCount", null);
            result.put("notice", e.getMessage());
        }
        return result;
    }

    /** 链不可达时统计接口降级为 0，避免整个接口 500 */
    private ChainStats collect() {
        ChainStats stats = new ChainStats();
        stats.setMode(chainGateway.mode());
        stats.setChainAvailable(chainGateway.available());
        try {
            long latest = chainGateway.latestBlockNumber();
            stats.setBlockCount(latest < 0 ? 0 : latest + 1);
        } catch (RuntimeException e) {
            stats.setBlockCount(0);
        }
        try {
            ChainCertCount count = chainGateway.countCertificates();
            stats.setTxCount(count.getTotal());
            stats.setActiveCount(count.getValid());
            stats.setRevokedCount(count.getRevoked());
            stats.setInvalidCount(count.getInvalid());
        } catch (RuntimeException e) {
            stats.setTxCount(0);
        }
        return stats;
    }
}
