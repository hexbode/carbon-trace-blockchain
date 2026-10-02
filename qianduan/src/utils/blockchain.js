/**
 * @file 区块链工具模块
 * @description 模拟联盟链的区块结构、哈希计算与存证验证逻辑
 *              用于前端演示"碳排放数据上链认证与追溯"的核心机制
 * @module 区块链工具
 */

// 简易 SHA-256 实现（同步，纯 JS，避免依赖外部库）
// 输入字符串，输出 64 位十六进制哈希值
function sha256(message) {
  // 右移位循环：将 32 位整数按指定位数循环右移
  function rightRotate(value, amount) {
    return (value >>> amount) | (value << (32 - amount))
  }
  const mathPow = Math.pow
  const maxWord = mathPow(2, 32)
  let i, j
  let result = ''
  const words = []
  const hash = sha256.h.slice()
  const k = sha256.k
  // 将输入字符串按 UTF-8 编码为字节数组，避免非 Latin-1 字符（如单位中的 '₂'）导致哈希失败
  const bytes = Array.from(new TextEncoder().encode(message))
  const bitLength = bytes.length * 8
  // 报文填充：补 0x80 标记位 + 后续补 0 直到字节长度满足 56 mod 64
  bytes.push(0x80)
  while (bytes.length % 64 !== 56) bytes.push(0)
  // 将字节序列按大端序转为 32 位字数组
  for (i = 0; i < bytes.length; i++) {
    words[i >> 2] = (words[i >> 2] || 0) | (bytes[i] << ((3 - (i % 4)) * 8))
  }
  // 附加原始报文比特长度（64 位大端表示）
  words[words.length] = (bitLength / maxWord) | 0
  words[words.length] = bitLength
  // 按 512 位（16 字）分块进行主压缩循环
  for (j = 0; j < words.length;) {
    const w = words.slice(j, j += 16)
    const oldHash = hash.slice(0)
    // 64 轮压缩：每轮更新 8 个工作变量
    for (i = 0; i < 64; i++) {
      const a = hash[0], b = hash[1], c = hash[2], d = hash[3], e = hash[4], f = hash[5], g = hash[6], h = hash[7]
      // 消息调度：i >= 16 时按 σ0/σ1 扩展 W[i]
      if (i >= 16) {
        const w15 = w[i - 15], w2 = w[i - 2]
        w[i] = ((rightRotate(w2, 17) ^ rightRotate(w2, 19) ^ (w2 >>> 10)) +
          w[i - 7] +
          (rightRotate(w15, 7) ^ rightRotate(w15, 18) ^ (w15 >>> 3)) +
          w[i - 16]) | 0
      }
      // Σ1(e)、ch(e,f,g)、Σ0(a)、maj(a,b,c)
      const S1 = rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25)
      const ch = (e & f) ^ (~e & g)
      const temp1 = (h + S1 + ch + k[i] + w[i]) | 0
      const S0 = rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const temp2 = (S0 + maj) | 0
      // 按标准顺序更新工作变量
      hash[0] = (temp1 + temp2) | 0
      hash[1] = a
      hash[2] = b
      hash[3] = c
      hash[4] = (d + temp1) | 0
      hash[5] = e
      hash[6] = f
      hash[7] = g
    }
    // 将本轮结果累加到上轮哈希
    for (i = 0; i < 8; i++) {
      hash[i] = (hash[i] + oldHash[i]) | 0
    }
  }
  // 将 8 个 32 位哈希字转为 64 位十六进制字符串
  for (i = 0; i < 8; i++) {
    for (j = 3; j + 1; j--) {
      const b = (hash[i] >> (j * 8)) & 255
      result += ((b < 16) ? 0 : '') + b.toString(16)
    }
  }
  return result
}
// SHA-256 初始哈希值（H0~H7）
sha256.h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
// SHA-256 轮常量数组（K0~K63，取自前 64 个质数立方根小数部分的前 32 位）
sha256.k = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
]

/**
 * 生成电子防伪码（Merkle 风格：对碳排放数据关键字段哈希）
 * @param {Object} data 碳排放数据，包含企业ID、报告日期、排放源、排放量、单位、时间戳
 * @returns {string} 64 位十六进制指纹
 */
export function generateDataFingerprint(data) {
  const payload = JSON.stringify({
    enterpriseId: data.enterpriseId,
    period: data.period,
    source: data.source,
    amount: data.amount,
    unit: data.unit,
    timestamp: data.timestamp || Date.now()
  })
  return sha256(payload)
}

/**
 * 生成交易哈希
 * @param {Object} payload 交易载荷
 * @returns {string} 以 0x 开头的 64 位十六进制交易哈希
 */
export function generateTxHash(payload) {
  return '0x' + sha256(JSON.stringify(payload) + Date.now() + Math.random()).slice(0, 64)
}

/**
 * 生成区块哈希
 * @param {number} index 区块高度
 * @param {string} previousHash 上一区块哈希
 * @param {number} timestamp 区块时间戳
 * @param {string} merkleRoot 默克尔根
 * @param {number} nonce 随机数
 * @returns {string} 以 0x 开头的 64 位十六进制区块哈希
 */
export function generateBlockHash(index, previousHash, timestamp, merkleRoot, nonce) {
  const header = `${index}|${previousHash}|${timestamp}|${merkleRoot}|${nonce}`
  return '0x' + sha256(header)
}

/**
 * 模拟 PoW 共识：寻找满足难度的 nonce
 * @param difficulty 难度（前导 0 的个数），默认 2
 * @returns {{nonce: number, hash: string}} 满足难度要求的 nonce 与对应区块哈希
 */
export function mineBlock(index, previousHash, timestamp, merkleRoot, difficulty = 2) {
  let nonce = 0
  const target = '0'.repeat(difficulty)
  let hash = ''
  while (true) {
    hash = generateBlockHash(index, previousHash, timestamp, merkleRoot, nonce)
    if (hash.slice(2, 2 + difficulty) === target) break
    nonce++
    if (nonce > 50000) break // 防止死循环
  }
  return { nonce, hash }
}

/**
 * 校验区块哈希完整性
 * @param {Object} block 待校验区块
 * @returns {boolean} 重新计算哈希是否与区块自带哈希一致
 */
export function verifyBlock(block) {
  const expected = generateBlockHash(block.index, block.previousHash, block.timestamp, block.merkleRoot, block.nonce)
  return expected === block.hash
}

/**
 * 校验整条链的完整性
 * @param {Array} chain 区块链数组
 * @returns {boolean} 每个区块自身哈希正确且 previousHash 链接关系正确
 */
export function verifyChain(chain) {
  for (let i = 0; i < chain.length; i++) {
    if (!verifyBlock(chain[i])) return false
    if (i > 0 && chain[i].previousHash !== chain[i - 1].hash) return false
  }
  return true
}

// 导出 sha256，供其它模块（如 mock 数据）复用
export { sha256 }

