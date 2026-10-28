package com.carbon.trace.chain.model;

import lombok.Data;

/**
 * CBAM 申报包链上锚点。
 *
 * 链上只保存「编号 + 快照哈希 + 数据版本 + 时间戳」，完整申报内容仍在业务库与导出 JSON 中；
 * {@code txHash} / {@code blockNumber} 仅在本次写链返回时回填，读取已有锚点时为 null。
 */
@Data
public class ChainAnchor {

    /** 申报包编号 */
    private String declarationNo;

    /** 申报年度 */
    private int reportYear;

    /** 生成快照时的数据版本 */
    private long dataVersion;

    /** 申报包 JSON 的 Keccak-256（64 位十六进制，不含 0x） */
    private String snapshotHash;

    /** 链上锚定时间（秒级时间戳） */
    private long anchoredAt;

    /** 锚定账户地址 */
    private String anchoredBy;

    /** 链上是否存在该申报包的锚点 */
    private boolean exists;

    /** 本次写链的交易哈希（仅写操作返回时填充） */
    private String txHash;

    /** 本次写链的区块高度（仅写操作返回时填充；未知为 -1） */
    private long blockNumber = -1L;
}
