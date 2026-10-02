/**
 * @file 文件上传接口
 */
import request from '@/utils/request'

// 上传头像：file 为 File 对象，返回 { url, name, size }
export const uploadAvatar = (file) => {
  const data = new FormData()
  data.append('file', file)
  return request.post('/files/avatar', data, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
