import type {
  ApiResponse,
  TelegramSettings, TelegramSettingsPayload, TelegramConnection,
  TelegramReport, TelegramReportDetail, TelegramDeliveryStatus, TelegramRecipientDelivery,
  ProposalSource,
  ProposalWrittenDraft,
  SavedProposalDraft,
  ProposalDraftState,
  CreateMonitoringRunResponse,
  DocumentDetail,
  DocumentDetection,
  LoginResponse,
  MonitoringRun,
  MonitoringRunWarnings,
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
  getTelegramSettings: () => request<TelegramSettings>('/api/telegram/settings'),
  updateTelegramSettings: (payload: TelegramSettingsPayload) =>
    request<TelegramSettings>('/api/telegram/settings', { method: 'PUT', body: JSON.stringify(payload) }),
  checkTelegramConnection: (expectedChatId: string) => request<TelegramConnection>('/api/telegram/connection-check', {
    method: 'POST', body: JSON.stringify({ expectedChatId }),
  }),
  sendTelegramTest: (expectedChatId: string) =>
    request<{ sent: boolean; message: string }>('/api/telegram/test-message', {
      method: 'POST', body: JSON.stringify({ expectedChatId }),
    }),
  getTelegramReports: (page: number, from: string, to: string, status: TelegramDeliveryStatus | '', signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(page), size: '10' })
    if (from) params.set('from', from)
    if (to) params.set('to', to)
    if (status) params.set('status', status)
    return request<PageResponse<TelegramReport>>(`/api/telegram/reports?${params}`, { signal })
  },
  getTelegramReport: (reportId: number, signal?: AbortSignal) =>
    request<TelegramReportDetail>(`/api/telegram/reports/${reportId}`, { signal }),
  retryTelegramReport: (deliveryId: number, expectedChatId: string, expectedAttemptCount: number) =>
    request<TelegramRecipientDelivery>(`/api/telegram/deliveries/${deliveryId}/retry`, {
      method: 'POST', body: JSON.stringify({ expectedChatId, expectedAttemptCount }),
    }),
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
  getRunWarnings: (runId: number, signal?: AbortSignal) =>
    request<MonitoringRunWarnings>(`/api/monitoring-runs/${runId}/warnings`, { signal }),
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
  getProposalSources: (detectionId: number, signal?: AbortSignal) =>
    request<ProposalSource[]>(`/api/document-detections/${detectionId}/proposal-sources`, { signal }),
  getProposalDrafts: (detectionId: number, signal?: AbortSignal) =>
    request<SavedProposalDraft[]>(`/api/document-detections/${detectionId}/proposal-drafts`, { signal }),
  getProposalDraftState: (detectionId: number, signal?: AbortSignal) =>
    request<ProposalDraftState>(`/api/document-detections/${detectionId}/proposal-drafts/state`, { signal }),
  rememberProposalDraft: (detectionId: number, attachmentId: number, partIndex: number) =>
    request<void>(`/api/document-detections/${detectionId}/proposal-drafts/last-viewed`, {
      method: 'PUT', body: JSON.stringify({ attachmentId, partIndex }),
    }),
  writeProposal: (detectionId: number, attachmentId: number, partIndex: number, signal?: AbortSignal) =>
    request<ProposalWrittenDraft>(`/api/document-detections/${detectionId}/proposal-draft`, {
      method: 'POST', body: JSON.stringify({ attachmentId, partIndex }), signal,
    }),
  regenerateProposal: (detectionId: number, payload: { attachmentId: number; partIndex: number; expectedRevision: number; operationId: string; feedback: string }, signal?: AbortSignal) =>
    request<ProposalWrittenDraft>(`/api/document-detections/${detectionId}/proposal-draft/regenerate`, {
      method: 'POST', body: JSON.stringify(payload), signal,
    }),
  restoreProposal: (detectionId: number, payload: { attachmentId: number; partIndex: number; expectedRevision: number; operationId: string }, signal?: AbortSignal) =>
    request<ProposalWrittenDraft>(`/api/document-detections/${detectionId}/proposal-draft/restore`, {
      method: 'POST', body: JSON.stringify(payload), signal,
    }),
  getSimilarNotices: (detectionId: number) =>
    request<SimilarNoticeResult>(`/api/document-detections/${detectionId}/similar-notices`),
}
