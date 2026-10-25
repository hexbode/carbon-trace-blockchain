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
 *
 * 约束：
 *  - 仅本进程内存缓存，重启后为空；未命中时会回退到区块反查（见 WebaseChainGateway），
 *    因此缓存缺失只影响性能、不影响正确性；
 *  - 两个 Map 的键口径必须一致（交易哈希统一归一化为小写且无 0x），否则会出现「写入能查、回查不到」；
 *  - 不做容量上限与过期淘汰：单条记录极小，按证书数量线性增长，适合当前业务量级。
 */
@Component
public class ChainTxLocator {

    /**
     * 一条存证的链上位置。
     *
     * @param txHash      交易哈希，缓存兜底写入时可能为 null
     * @param blockNumber 区块高度，可能为 null（回执未给出高度时）
     */
    public record Entry(String txHash, Long blockNumber) {
    }

    private final Map<String, Entry> byCertId = new ConcurrentHashMap<>();
    private final Map<String, String> certIdByTxHash = new ConcurrentHashMap<>();

    /**
     * 记录一笔存证的位置信息：有交易哈希则以其为准覆盖写入；无哈希仅有区块高度时仅作兜底（不覆盖既有值）。
     *
     * 覆盖策略的原因：同一证书只会有一次成功认证，交易哈希是权威值；而「只有区块高度」的来源
     * （如区块反查失败后回填）信息更弱，不能覆盖已知哈希。
     *
     * @param certId      认证编号，为 null 时直接忽略
     * @param txHash      交易哈希，可为 null
     * @param blockNumber 区块高度，可为 null
     */
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

    /**
     * 按认证编号返回已缓存的交易哈希 / 区块高度；无缓存或编号为空时返回空。
     *
     * @param certId 认证编号，可为 null
     * @return 位置信息；未命中返回空 Optional
     */
    public Optional<Entry> lookup(String certId) {
        return certId == null ? Optional.empty() : Optional.ofNullable(byCertId.get(certId));
    }

    /**
     * 批量取缓存（列表页不回链，只回填本地已知交易哈希/区块高度）。
     *
     * @param certIds 认证编号集合，可为 null
     * @return 命中的编号 → 位置信息映射，恒不为 null，未命中的编号不出现
     */
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

    /**
     * 按归一化交易哈希反查认证编号，便于「按交易哈希核验」时快速定位且避免回链。
     *
     * @param txHash 交易哈希，大小写与 0x 前缀不敏感
     * @return 认证编号；未命中或入参为 null 时返回空 Optional
     */
    public Optional<String> certIdByTxHash(String txHash) {
        if (txHash == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(certIdByTxHash.get(normalize(txHash)));
    }

    /**
     * 哈希归一化：去首尾空白、转小写、去掉 0x 前缀，作为两个缓存 Map 的统一键。
     *
     * @param value 原始哈希，不能为 null
     * @return 归一化后的哈希
     */
    private static String normalize(String value) {
        String v = value.trim().toLowerCase();
        return v.startsWith("0x") ? v.substring(2) : v;
    }
}
