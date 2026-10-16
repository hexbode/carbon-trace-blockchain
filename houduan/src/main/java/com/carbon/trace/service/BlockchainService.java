package com.carbon.trace.service;

import java.util.List;
import java.util.Map;

/**
 * 区块链服务（薄适配层）：对外契约保持不变，数据全部来自 {@code ChainGateway}。
 */
public interface BlockchainService {

    /** 最近若干个区块（含交易明细） */
    List<Map<String, Object>> listBlocks(int limit);

    /** 链统计：{blockCount, txCount, activeCount, revokedCount, invalidCount, mode, chainAvailable} */
    Map<String, Object> stats();

    /** 链健康信息：模式、可用性、最新区块、合约地址等 */
    Map<String, Object> health();
}
