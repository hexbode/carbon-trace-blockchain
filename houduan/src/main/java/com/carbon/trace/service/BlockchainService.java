package com.carbon.trace.service;

import java.util.List;
import java.util.Map;

/**
 * 区块链服务
 */
public interface BlockchainService {

    /** 全部区块（含交易明细） */
    List<Map<String, Object>> listBlocks();

    /** 区块链统计：{blockCount, txCount} */
    Map<String, Object> stats();

    /** 将一笔认证交易写入链上（每 4 笔打包成一个区块） */
    Integer appendTransaction(Map<String, Object> tx);

    /** 移除某个排放记录对应的链上交易（记录被删除时调用） */
    void removeByEmissionId(String emissionId);
}
