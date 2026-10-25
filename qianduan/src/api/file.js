/**
 * @file 文件上传接口
 * @description 用途：个人中心头像上传（后端保存到本地磁盘，数据库只存相对地址）。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：uploadAvatar。
 *              对应后端：FileController（@RequestMapping("/api/files")）。
 */
import request from '@/utils/request'

/**
 * 上传头像：file 为 File 对象，返回 { url, name, size }
 *
 * 接口：POST /files/avatar（multipart/form-data）
 * @param {File} file 图片文件；<=5MB，仅支持 jpg/jpeg/png/gif/webp，且扩展名需与文件内容一致
 * @returns {Promise<{url: string, name: string, size: number}>} 头像访问地址、清洗后的原始文件名、字节数
 * @permission 需登录（接口不在免登录白名单）
 */
export const uploadAvatar = (file) => {
  const data = new FormData()
  data.append('file', file)
  return request.post('/files/avatar', data, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
