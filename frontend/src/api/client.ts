import type {
  ApiResponse,
  CreateMonitoringRunResponse,
  DocumentDetail,
  DocumentDetection,
  LoginResponse,
  MonitoringRun,
  MonitoringSource,
  MonitoringSourcePayload,
  PageResponse,
} from './types'

const TOKEN_KEY = 'govinsight.accessToken'

export const authStore = {
  get: () => sessionStorage.getItem(TOKEN_KEY),
  set: (token: string) => sessionStorage.setItem(TOKEN_KEY, token),
  clear: () => sessionStorage.removeItem(TOKEN_KEY),
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = authStore.get()
  const response = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  })
  const body = (await response.json().catch(() => null)) as ApiResponse<T> | null
  if (!response.ok || !body?.isSuccess) {
    if (response.status === 401) authStore.clear()
    throw new ApiError(body?.message ?? '요청을 처리하지 못했습니다.', response.status)
  }
  return body.data
}

export const api = {
  login: (loginId: string, password: string) =>
    request<LoginResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ loginId, password }),
    }),
  getSources: () => request<MonitoringSource[]>('/api/monitoring-sources'),
  getSource: (sourceId: number) => request<MonitoringSource>(`/api/monitoring-sources/${sourceId}`),
  createSource: (payload: MonitoringSourcePayload) =>
    request<MonitoringSource>('/api/monitoring-sources', { method: 'POST', body: JSON.stringify(payload) }),
  updateSource: (sourceId: number, payload: MonitoringSourcePayload) =>
    request<MonitoringSource>(`/api/monitoring-sources/${sourceId}`, { method: 'PUT', body: JSON.stringify(payload) }),
  changeSourceEnabled: (sourceId: number, enabled: boolean) =>
    request<MonitoringSource>(`/api/monitoring-sources/${sourceId}/enabled`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled }),
    }),
  createRun: () => request<CreateMonitoringRunResponse>('/api/monitoring-runs', { method: 'POST' }),
  getRuns: (page = 0, size = 10) =>
    request<PageResponse<MonitoringRun>>(`/api/monitoring-runs?page=${page}&size=${size}`),
  getDocuments: (page = 0, size = 20) =>
    request<PageResponse<DocumentDetection>>(`/api/document-detections?page=${page}&size=${size}`),
  getDocument: (detectionId: number) => request<DocumentDetail>(`/api/document-detections/${detectionId}`),
}
