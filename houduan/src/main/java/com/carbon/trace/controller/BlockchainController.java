package com.carbon.trace.controller;

import com.carbon.trace.service.BlockchainService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 区块链接口：展示联盟链的区块、交易、统计与健康信息，数据取自 WeBASE 联盟链上的合约，
 * 离线桩模式下对应数据为空。
 *
 * 暴露接口：
 *   GET /api/blockchain         最近若干区块（含交易明细），limit 不传时取配置 carbon.chain.block-list-limit；
 *   GET /api/blockchain/stats   链上统计指标；
 *   GET /api/blockchain/health  链健康信息（模式、可用性、最新区块、合约地址），供运维排障。
 * 角色约定：接口均需登录（经 AuthInterceptor 鉴权）。
 *
 * 依赖：{@link BlockchainService}（把链网关数据组装为前端结构；本控制器不直接调用 WeBASE-Front）。
 * 安全边界与降级策略：
 *   1. 三个接口均只读，不回写链、不落库；
 *   2. 区块与统计接口会访问链节点，链不可达时可能返回 502/503（由链层映射）；
 *      stats 内部对链异常做了降级（计数归零），health 始终 200 并在 notice 中给出异常原因；
 *   3. 接口只要求登录、未按角色限制，链上交易明细包含企业名称与排放量，属于对外可见的存证数据。
 */
@RestController
@RequestMapping("/api/blockchain")
public class BlockchainController {

    private final BlockchainService blockchainService;

    /** 构造注入区块链服务；控制器无状态 */
    public BlockchainController(BlockchainService blockchainService) {
        this.blockchainService = blockchainService;
    }

    /**
     * GET /api/blockchain —— 最近若干区块（含交易明细）。
     *
     * 权限：登录即可。
     * 参数：limit 区块数量上限，缺省 0 表示取 carbon.chain.block-list-limit。
     * 返回：List，每项 {index, hash, previousHash, timestamp, merkleRoot, nonce, miner, transactions[]}。
     * 错误码：401 未登录；502/503 链层调用失败或 WeBASE-Front 不可达；500 未知异常。
     * 链上写：无（只读查询）。
     */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "0") int limit) {
        return blockchainService.listBlocks(limit);
    }

    /**
     * GET /api/blockchain/stats —— 链上统计指标。
     *
     * 权限：登录即可。
     * 返回：{blockCount, txCount, activeCount, revokedCount, invalidCount, mode, chainAvailable}；
     * 链不可达时计数降级为 0，chainAvailable=false，不抛错。
     * 错误码：401 未登录；500 未知异常。
     * 链上写：无（只读查询）。
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return blockchainService.stats();
    }

    /**
     * GET /api/blockchain/health —— 链健康信息。
     *
     * 权限：登录即可。
     * 返回：{mode, available, groupId, registryAddress, complianceAddress, signMode,
     * latestBlockNumber, chainTxCount}，链异常时后两项为 null 并追加 notice。
     * 错误码：401 未登录；本接口恒返回 200（链故障通过 notice 表达），500 仅限未知异常。
     * 链上写：无（只读查询）。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return blockchainService.health();
    }
}
