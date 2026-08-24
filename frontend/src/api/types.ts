export interface ApiResponse<T> {
  isSuccess: boolean
  timestamp: string
  code: string
  httpStatus: number
  message: string
  data: T
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
}

export interface MonitoringSource {
  sourceId: number
  organizationName: string
  boardName: string
  description: string | null
  listUrl: string
  urlIncludePattern: string | null
  detailFetchCount: number
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface MonitoringSourcePayload {
  organizationName: string
  boardName: string
  description: string | null
  listUrl: string
  urlIncludePattern: string | null
  detailFetchCount: number
  enabled: boolean
}

export type RunStatus = 'REQUESTED' | 'ACCEPTED' | 'COLLECTED' | 'COMPLETED' | 'FAILED'

export interface MonitoringRun {
  runId: number
  requestedAt: string
  triggerType: 'MANUAL' | 'SCHEDULED'
  status: RunStatus
  totalSourceCount: number
  detectedDocumentCount: number
  warningCount: number
  reportTitle: string | null
}

export interface CreateMonitoringRunResponse {
  runId: number
  status: RunStatus
  triggerType: 'MANUAL' | 'SCHEDULED'
  totalSourceCount: number
  requestedAt: string
}

export type Importance = 'HIGH' | 'NORMAL' | 'LOW'
export type ChangeType = 'NEW_DOCUMENT' | 'UPDATED_DOCUMENT' | 'UNCHANGED_DOCUMENT'

export interface DocumentDetection {
  detectionId: number
  documentId: number
  versionId: number
  organizationName: string
  boardName: string
  title: string
  changeType: ChangeType
  attachmentCount: number
  importance: Importance | null
  lastCheckedAt: string
}

export interface DocumentAnalysis {
  summary: string
  keyPoints: string[]
  importance: Importance
  reason: string
  eligibility: 'ELIGIBLE' | 'INELIGIBLE' | 'REVIEW_REQUIRED'
  favorableOrNot: 'FAVORABLE' | 'UNFAVORABLE' | 'NEUTRAL' | 'NOT_APPLICABLE' | 'REVIEW_REQUIRED'
  proposal: {
    sections: Array<{ title: string; body: string }>
  }
}

export interface DocumentAttachment {
  fileName: string
  fileExtension: string
  fileSize: number | null
  parseStatus: 'COMPLETED' | 'FAILED' | 'UNSUPPORTED'
  downloadUrl: string
}

export interface DocumentDetail {
  detectionId: number
  organizationName: string
  boardName: string
  title: string
  publishedAt: string | null
  changeType: ChangeType
  originalUrl: string
  lastCheckedAt: string
  analysis: DocumentAnalysis | null
  attachments: DocumentAttachment[]
}
