import type {
  ApiResponse,
  CreateMonitoringRunResponse,
  DocumentDetail,
  DocumentDetection,
  LoginResponse,
  MonitoringRun,
  MonitoringSchedule,
  MonitoringSchedulePayload,
  MonitoringSource,
  MonitoringSourcePayload,
  SimilarNoticeResult,
  LegalPairResult,
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
  getMonitoringSchedule: () => request<MonitoringSchedule>('/api/monitoring-schedule'),
  updateMonitoringSchedule: (payload: MonitoringSchedulePayload) =>
    request<MonitoringSchedule>('/api/monitoring-schedule', {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  getDocuments: (
    page = 0,
    size = 20,
    from?: string,
    to?: string,
    runId?: number,
    sort: 'LATEST' | 'OPPORTUNITY_SCORE' = 'LATEST',
    savedOnly = false,
  ) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (from) params.set('from', from)
    if (to) params.set('to', to)
    if (runId) params.set('runId', String(runId))
    params.set('sort', sort)
    return request<PageResponse<DocumentDetection>>(`${savedOnly ? "/api/bookmarks/documents" : "/api/document-detections"}?${params}`)
  },
  getBookmarkIds: () => request<number[]>('/api/bookmarks/versions'),
  setBookmark: (versionId: number, saved: boolean) =>
    request<void>(`/api/bookmarks/versions/${versionId}`, { method: saved ? 'PUT' : 'DELETE' }),
  getDocument: (detectionId: number) => request<DocumentDetail>(`/api/document-detections/${detectionId}`),
  compareLegalPair: (currentId: number, similarId: number, signal?: AbortSignal) =>
    request<LegalPairResult>(`/api/document-detections/${currentId}/similar-notices/${similarId}/legal-review`, { method: 'POST', signal }),
  getSimilarNotices: (detectionId: number) =>
    request<SimilarNoticeResult>(`/api/document-detections/${detectionId}/similar-notices`),
}
