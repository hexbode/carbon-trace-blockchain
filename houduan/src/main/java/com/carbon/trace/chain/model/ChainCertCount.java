package com.carbon.trace.chain.model;

import lombok.Data;

/**
 * 链上证书计数（供一致性核验与统计使用）。
 *
 * 四个字段是链上互斥状态的分桶计数，关系为 total = valid + revoked + invalid；
 * 它们由合约计数器直接给出，不通过遍历推导，因此可用于对账（见 ChainIntegrityChecker）。
 * 注意 total 是历史累计，含已作废存证，不能当作「当前存证数」使用。
 */
@Data
public class ChainCertCount {

    /** 历史累计认证数 */
    private long total;

    /** 当前有效 */
    private long valid;

    /** 已撤销 */
    private long revoked;

    /** 已作废（业务记录被删除） */
    private long invalid;
}
