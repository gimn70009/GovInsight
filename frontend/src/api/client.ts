import type {
  ApiResponse, EmailSettings, EmailSettingsPayload, EmailRecipientDelivery, ReportDelivery, ReportDeliveryDetail, DeliveryChannel,
  TelegramSettings, TelegramSettingsPayload, TelegramConnection,
  TelegramReport, TelegramReportDetail, TelegramDeliveryStatus, TelegramRecipientDelivery,
  ProposalSource,
  ProposalTemplateInspection,
  ProposalWrittenDraft,
  SavedProposalDraft,
  ProposalDraftState,
  CreateMonitoringRunResponse,
  DocumentDetail,
  DocumentDetection,
  LoginResponse,
  MonitoringRun,
  MonitoringRunActivity,
  MonitoringRunWarnings,
  MonitoringSchedule,
  MonitoringSchedulePayload,
  MonitoringSource,
  MonitoringSourceSettings,
  SimilarNoticeResult,
  LegalPairResult,
  PageResponse,
  OpportunityPriority,
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
  getEmailSettings: () => request<EmailSettings>('/api/email/settings'),
  updateEmailSettings: (payload: EmailSettingsPayload) => request<EmailSettings>('/api/email/settings', { method: 'PUT', body: JSON.stringify(payload) }),
  sendEmailTest: (expectedAddress: string) => request<{ sent: boolean; message: string }>('/api/email/test-message', { method: 'POST', body: JSON.stringify({ expectedAddress }) }),
  retryEmailReport: (deliveryId: number, expectedAddress: string, expectedAttemptCount: number) => request<EmailRecipientDelivery>(`/api/email/deliveries/${deliveryId}/retry`, { method: 'POST', body: JSON.stringify({ expectedAddress, expectedAttemptCount }) }),
  getReportDeliveries: (page: number, from: string, to: string, channel: DeliveryChannel, status: TelegramDeliveryStatus | '', signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(page), size: '10', channel })
    if (from) params.set('from', from)
    if (to) params.set('to', to)
    if (status) params.set('status', status)
    return request<PageResponse<ReportDelivery>>(`/api/report-deliveries?${params}`, { signal })
  },
  getReportDelivery: (id: number, signal?: AbortSignal) => request<ReportDeliveryDetail>(`/api/report-deliveries/${id}`, { signal }),
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
  getSources: (signal?: AbortSignal) => request<MonitoringSource[]>('/api/monitoring-sources', { signal }),
  getSource: (sourceId: number) => request<MonitoringSource>(`/api/monitoring-sources/${sourceId}`),
  updateSourceSettings: (sources: MonitoringSourceSettings[]) =>
    request<MonitoringSource[]>('/api/monitoring-sources/settings', {
      method: 'PATCH', body: JSON.stringify({ sources }),
    }),
  getRunActivity: (signal?: AbortSignal) => request<MonitoringRunActivity>('/api/monitoring-runs/active', { signal }),
  createRun: () => request<CreateMonitoringRunResponse>('/api/monitoring-runs', { method: 'POST' }),
  getRuns: (page = 0, size = 10) =>
    request<PageResponse<MonitoringRun>>(`/api/monitoring-runs?page=${page}&size=${size}`),
  getRunWarnings: (runId: number, signal?: AbortSignal) =>
    request<MonitoringRunWarnings>(`/api/monitoring-runs/${runId}/warnings`, { signal }),
  getMonitoringSchedule: (signal?: AbortSignal) => request<MonitoringSchedule>('/api/monitoring-schedule', { signal }),
  updateMonitoringSchedule: (payload: MonitoringSchedulePayload) =>
    request<MonitoringSchedule>('/api/monitoring-schedule', {
      method: 'PUT',
      body: JSON.stringify({ enabled: payload.enabled, frequency: payload.frequency, executionTime: payload.executionTime, customDays: payload.customDays }),
    }),
  cancelPendingSchedule: (scheduledAt: string) =>
    request<MonitoringSchedule>(`/api/monitoring-schedule/pending?${new URLSearchParams({ scheduledAt })}`, { method: 'DELETE' }),
  getDocuments: (
    page = 0,
    size = 20,
    from?: string,
    to?: string,
    runId?: number,
    sort: 'LATEST' | 'OPPORTUNITY_SCORE' = 'LATEST',
    savedOnly = false,
    query = '',
    priority?: OpportunityPriority,
    signal?: AbortSignal,
  ) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (from) params.set('from', from)
    if (to) params.set('to', to)
    if (runId) params.set('runId', String(runId))
    params.set('sort', sort)
    if (query.trim()) params.set('query', query.trim())
    if (priority) params.set('priority', priority)
    return request<PageResponse<DocumentDetection>>(`${savedOnly ? "/api/bookmarks/documents" : "/api/document-detections"}?${params}`, { signal })
  },
  getBookmarkIds: () => request<number[]>('/api/bookmarks/versions'),
  setBookmark: (versionId: number, saved: boolean) =>
    request<void>(`/api/bookmarks/versions/${versionId}`, { method: saved ? 'PUT' : 'DELETE' }),
  getDocument: (detectionId: number) => request<DocumentDetail>(`/api/document-detections/${detectionId}`),
  compareLegalPair: (currentId: number, similarId: number, signal?: AbortSignal) =>
    request<LegalPairResult>(`/api/document-detections/${currentId}/similar-notices/${similarId}/legal-review`, { method: 'POST', signal }),
  getProposalSources: (detectionId: number, signal?: AbortSignal) =>
    request<ProposalSource[]>(`/api/document-detections/${detectionId}/proposal-sources`, { signal }),
  inspectProposalSource: (detectionId: number, attachmentId: number, partIndex: number, signal?: AbortSignal) =>
    request<ProposalTemplateInspection>(`/api/document-detections/${detectionId}/proposal-sources/inspect`, {
      method: 'POST', body: JSON.stringify({ attachmentId, partIndex }), signal,
    }),
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
