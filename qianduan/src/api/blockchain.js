/**
 * @file 区块链接口
 */
import request from '@/utils/request'

// 区块链全部区块
export const getBlockchain = () => request.get('/blockchain')
// 区块链统计：{ blockCount, txCount }
export const getBlockchainStats = () => request.get('/blockchain/stats')
