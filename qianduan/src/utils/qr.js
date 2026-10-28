/**
 * @file 证书核验链接生成
 * @description 二维码内容为公共端核验链接，基地址支持配置，避免硬编码端口。
 *
 * 取值优先级：
 *   1. 构建期环境变量 VITE_QR_BASE_URL（如 https://carbon.example.com），为空则忽略；
 *   2. HTTPS 访问：沿用当前 origin；
 *   3. HTTP 访问：回退到 https://<当前主机>:8443。
 *
 * 为什么 HTTP 下要指向 HTTPS：
 *   网页摄像头/扫码只在安全上下文（HTTPS 或 localhost）可用。企业端若以 HTTP 访问，
 *   二维码写同源 HTTP 地址会让手机打开后无法调用摄像头，因此默认改写到同主机的 HTTPS 端口。
 */

/** 构建期配置的核验站点基地址（去掉末尾斜杠）；未配置为空串 */
const CONFIGURED_BASE = (import.meta.env.VITE_QR_BASE_URL || '').trim().replace(/\/+$/, '')

/** HTTP 回退时使用的 HTTPS 端口，可通过 VITE_QR_HTTPS_PORT 覆盖 */
const HTTPS_PORT = (import.meta.env.VITE_QR_HTTPS_PORT || '8443').trim()

/**
 * 核验站点基地址。
 *
 * @returns {string} 形如 https://host:8443（无末尾斜杠）
 */
export function verificationBase() {
  if (CONFIGURED_BASE) return CONFIGURED_BASE
  if (window.location.protocol === 'https:') return window.location.origin
  return `https://${window.location.hostname}:${HTTPS_PORT}`
}

/**
 * 公共核验页链接（hash 路由 /#/public/query）。
 *
 * @param {string} fingerprint 电子防伪码
 * @returns {string} 可直接扫码打开的核验链接
 */
export function buildQrPayload(fingerprint) {
  return `${verificationBase()}/#/public/query?fp=${encodeURIComponent(fingerprint)}`
}

/**
 * HTTP 访问时提示用户改用 HTTPS 的地址（用于摄像头不可用提示）。
 *
 * @param {string} [hashPath] hash 路由，如 /#/public/qr
 * @returns {string} HTTPS 访问地址
 */
export function httpsHintUrl(hashPath = '') {
  if (CONFIGURED_BASE) return `${CONFIGURED_BASE}${hashPath}`
  return `https://${window.location.hostname}:${HTTPS_PORT}${hashPath}`
}
