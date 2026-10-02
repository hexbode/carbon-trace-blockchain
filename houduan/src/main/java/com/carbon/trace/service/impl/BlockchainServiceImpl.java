package com.carbon.trace.service.impl;

import com.carbon.trace.chain.Block;
import com.carbon.trace.chain.BlockTransaction;
import com.carbon.trace.chain.ChainStore;
import com.carbon.trace.service.BlockchainService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区块链服务实现：链上数据保存在文件中（{@link ChainStore}），数据库中不保存任何哈希。
 */
@Service
public class BlockchainServiceImpl implements BlockchainService {

    private final ChainStore chainStore;

    public BlockchainServiceImpl(ChainStore chainStore) {
        this.chainStore = chainStore;
    }

    @Override
    public List<Map<String, Object>> listBlocks() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Block block : chainStore.blocks()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", block.getBlockIndex());
            item.put("hash", block.getHash());
            item.put("previousHash", block.getPreviousHash());
            item.put("timestamp", block.getTimestamp());
            item.put("merkleRoot", block.getMerkleRoot());
            item.put("nonce", block.getNonce());
            item.put("miner", block.getMiner());

            List<Map<String, Object>> txList = new ArrayList<>();
            if (block.getTransactions() != null) {
                for (BlockTransaction tx : block.getTransactions()) {
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("blockCount", chainStore.blockCount());
        result.put("txCount", chainStore.txCount());
        return result;
    }

    @Override
    public Integer appendTransaction(Map<String, Object> tx) {
        return chainStore.appendTransaction(tx);
    }

    @Override
    public void removeByEmissionId(String emissionId) {
        chainStore.removeByEmissionId(emissionId);
    }
}
