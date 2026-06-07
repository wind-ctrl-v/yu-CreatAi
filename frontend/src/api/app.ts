import request, { API_BASE_URL } from '../request'
import type { AppQueryRequest, AppVO, BaseResponse, Page } from '../types'

/** 创建应用（返回 appId） */
export function addApp(params: { initPrompt: string }) {
  return request.post<any, BaseResponse<string>>('/app/add', params)
}

/** 根据 id 获取应用详情 */
export function getAppVOById(id: string) {
  return request.get<any, BaseResponse<AppVO>>('/app/get/vo', { params: { id } })
}

/** 分页获取我的应用 */
export function listMyAppByPage(params: AppQueryRequest) {
  return request.post<any, BaseResponse<Page<AppVO>>>('/app/my/list/page/vo', params)
}

/** 分页获取精选应用 */
export function listGoodAppByPage(params: AppQueryRequest) {
  return request.post<any, BaseResponse<Page<AppVO>>>('/app/good/list/page/vo', params)
}

/** 更新应用（改名） */
export function updateApp(params: { id: string; appName: string }) {
  return request.post<any, BaseResponse<boolean>>('/app/update', params)
}

/** 删除应用 */
export function deleteApp(id: string) {
  return request.post<any, BaseResponse<boolean>>('/app/delete', { id })
}

/** 部署应用，返回访问 URL */
export function deployApp(appId: string) {
  return request.post<any, BaseResponse<string>>('/app/deploy', { appId })
}

/** 构建下载 ZIP 的直链（GET，浏览器带 Cookie 直接访问） */
export function buildDownloadUrl(appId: string) {
  return `${API_BASE_URL}/app/download/${appId}`
}

/** 构建生成产物的实时预览 URL */
export function buildPreviewUrl(codeGenType: string, appId: string) {
  return `${API_BASE_URL}/static/${codeGenType}_${appId}/`
}
