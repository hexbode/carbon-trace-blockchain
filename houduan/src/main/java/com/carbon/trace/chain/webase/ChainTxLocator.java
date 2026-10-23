package com.carbon.trace.chain.webase;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易定位缓存：
 *  - certId →（交易哈希, 区块高度）：回填凭证的展示字段；
 *  - 归一化交易哈希 → certId：按交易哈希核验时快速定位。
 *
 * 合约自身无法知道自己那笔交易的哈希，因此 txHash / blockIndex 只能靠交易回执或事件日志反查。
 * 缓存可长期复用：证书的 createdBlock 与它的 CertCreated 交易哈希一旦上链就不会改变。
 */
@Component
public class ChainTxLocator {

    public record Entry(String txHash, Long blockNumber) {
    }

    private final Map<String, Entry> byCertId = new ConcurrentHashMap<>();
    private final Map<String, String> certIdByTxHash = new ConcurrentHashMap<>();

    public void remember(String certId, String txHash, Long blockNumber) {
        if (certId == null) {
            return;
        }
        if (txHash != null) {
            byCertId.put(certId, new Entry(txHash, blockNumber));
            certIdByTxHash.put(normalize(txHash), certId);
        } else if (blockNumber != null) {
            byCertId.putIfAbsent(certId, new Entry(null, blockNumber));
        }
    }

    public Optional<Entry> lookup(String certId) {
        return certId == null ? Optional.empty() : Optional.ofNullable(byCertId.get(certId));
    }

    /** 批量取缓存（列表页不回链，只回填本地已知交易哈希/区块高度） */
    public java.util.Map<String, Entry> lookupAll(java.util.Collection<String> certIds) {
        java.util.Map<String, Entry> found = new java.util.HashMap<>();
        if (certIds == null) {
            return found;
        }
        for (String certId : certIds) {
            if (certId == null) {
                continue;
            }
            Entry entry = byCertId.get(certId);
            if (entry != null) {
                found.put(certId, entry);
            }
        }
        return found;
    }

    public Optional<String> certIdByTxHash(String txHash) {
        if (txHash == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(certIdByTxHash.get(normalize(txHash)));
    }

    private static String normalize(String value) {
        String v = value.trim().toLowerCase();
        return v.startsWith("0x") ? v.substring(2) : v;
    }
}
