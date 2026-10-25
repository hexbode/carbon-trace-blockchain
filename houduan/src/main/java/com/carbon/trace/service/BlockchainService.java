package com.carbon.trace.service;

import java.util.List;
import java.util.Map;

/**
 * 区块链服务（薄适配层）：对外契约保持不变，数据全部来自 {@code ChainGateway}。
 * 只负责把链网关返回的区块、交易与状态数据组装为前端友好的 Map，不持久化业务数据、不发起链上写操作；
 * 链不可达时统计接口降级为 0，健康接口在 notice 中透出异常原因，避免整体 500。
 * 权限：接口需登录（由 AuthInterceptor 统一鉴权）。
 */
public interface BlockchainService {

    /**
     * 查询最近若干个区块（含交易明细）。
     *
     * @param limit 返回区块数上限；小于等于 0 时取配置 carbon.chain.block-list-limit
     * @return 区块列表，每项包含 index/hash/previousHash/timestamp/merkleRoot/nonce/miner 与 transactions
     */
    List<Map<String, Object>> listBlocks(int limit);

    /**
     * 链统计：{blockCount, txCount, activeCount, revokedCount, invalidCount, mode, chainAvailable}。
     * 链不可达时计数类指标降级为 0，mode/chainAvailable 仍反映当前链模式。
     *
     * @return 统计 Map
     */
    Map<String, Object> stats();

    /**
     * 链健康信息：模式、可用性、最新区块、合约地址等，供运维排障。
     * 链不可达时 latestBlockNumber/chainTxCount 返回 null，并在 notice 中给出异常信息。
     *
     * @return 健康信息 Map
     */
    Map<String, Object> health();
}
