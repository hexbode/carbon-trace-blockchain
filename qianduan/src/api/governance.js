/**
 * @file 链上治理接口
 * @description 查看合约 owner / writer 状态，并由管理员增删写入白名单。
 *              对应后端：ChainGovernanceController（@RequestMapping("/api/chain-governance")）。
 */
import request from '@/utils/request'

/** 治理总览：模式、逻辑版本、签名账户、三类合约状态 */
export const getGovernanceStatus = () => request.get('/chain-governance/status')

/** 查询任意地址在指定合约上的写入权限：params = { kind, account } */
export const getWriterStatus = (params) => request.get('/chain-governance/writer', { params })

/** 增删写入白名单（仅 admin）：data = { kind, account, allowed } */
export const setWriter = (data) => request.post('/chain-governance/writers', data)
