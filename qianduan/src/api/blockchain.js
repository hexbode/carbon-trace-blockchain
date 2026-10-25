/**
 * @file 区块链接口
 * @description 用途：查询链上区块明细与统计指标，供区块链展示区与布局顶部区块高度使用。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：getBlockchain、getBlockchainStats。
 *              对应后端：BlockchainController（@RequestMapping("/api/blockchain")）。
 */
import request from '@/utils/request'

/**
 * 区块链全部区块
 *
 * 接口：GET /blockchain（后端最近若干区块，数量由 carbon.chain.block-list-limit 控制）
 * @returns {Promise<Array>} 区块列表，每项含 index、hash、previousHash、timestamp、merkleRoot、
 *                          nonce、miner 与 transactions 交易明细
 * @permission 登录即可；链节点不可达时返回 502/503
 */
export const getBlockchain = () => request.get('/blockchain')

/**
 * 区块链统计：{ blockCount, txCount }
 *
 * 接口：GET /blockchain/stats
 * @returns {Promise<Object>} { blockCount, txCount, activeCount, revokedCount, invalidCount, mode, chainAvailable }；
 *                            链不可达时计数降级为 0、chainAvailable=false
 * @permission 登录即可
 */
export const getBlockchainStats = () => request.get('/blockchain/stats')
