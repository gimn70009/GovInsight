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
export type OpportunityPriority = 'HIGH' | 'NORMAL' | 'LOW'
export type ChangeType = 'NEW_DOCUMENT' | 'UPDATED_DOCUMENT' | 'UNCHANGED_DOCUMENT'

export interface DocumentDetection {
  runId: number
  detectionId: number
  documentId: number
  versionId: number
  organizationName: string
  boardName: string
  title: string
  changeType: ChangeType
  attachmentCount: number
  importance: Importance | null
  opportunityScore: number | null
  opportunityPriority: OpportunityPriority | null
  lastCheckedAt: string
}

export type OpportunityDimensionType =
  | 'COMPANY_FIT'
  | 'BUSINESS_VALUE'
  | 'FEASIBILITY'
  | 'URGENCY'
  | 'EVIDENCE_CONFIDENCE'

export interface OpportunityAssessment {
  totalScore: number
  priority: OpportunityPriority
  dimensions: Array<{
    type: OpportunityDimensionType
    score: number
    reason: string
  }>
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
    documentType: 'GENERAL_NOTICE' | 'BUSINESS_NOTICE' | 'PROPOSAL_REQUEST' | 'REVIEW_REQUIRED'
    draftStatus: 'NOT_APPLICABLE' | 'READY' | 'REVIEW_REQUIRED' | 'NOT_RECOMMENDED' | 'GENERATING'
    draftReason: string
    sourceAttachmentNames: string[]
    templateSections: string[]
    draftSections: Array<{ title: string; body: string }>
    preparationSchemaVersion?: number
    preparation: {
      meetingAgenda: string[]
      eligibilityChecklist: ProposalPreparationItem[]
      submissionDocuments: ProposalPreparationItem[]
      companyInputs: ProposalPreparationItem[]
      strategy: {
        decision?: 'GO' | 'CONDITIONAL_GO' | 'HOLD' | 'NO_GO' | null
        decisionReason?: string | null
        recommendedProject?: string | null
        recommendedParticipation?: string | null
        alternativeParticipation?: string | null
        capabilityMatches?: Array<{ confirmedFact: string; strategicInterpretation: string }> | null
        criticalGaps?: Array<{ gap: string; nextAction: string; owner: string; targetTiming: string }> | null
        stopCriteria?: Array<{ type: 'OFFICIAL_REQUIREMENT' | 'INTERNAL_RECOMMENDATION'; condition: string; rationale: string }> | null
      }
    } | null
  }
  opportunity: OpportunityAssessment | null
}

export interface ProposalPreparationItem {
  title: string
  status:
    | 'READY'
    | 'VERIFIED'
    | 'LIKELY'
    | 'ACTION_REQUIRED'
    | 'NEEDS_CONFIRMATION'
    | 'MISSING'
    | 'INELIGIBLE'
    | 'NOT_APPLICABLE'
  detail: string
  nextAction: string
  requirementLevel?: 'MANDATORY' | 'CONDITIONAL' | 'OPTIONAL' | 'RECOMMENDED' | null
  stage?: 'APPLICATION' | 'EVALUATION' | 'POST_SELECTION' | 'AGREEMENT' | 'EXECUTION' | 'REPORTING' | null
  appliesTo?: string | null
  source?: {
    origin: 'NOTICE_BODY' | 'ATTACHMENT' | 'COMPANY_PROFILE' | 'COMPANY_INPUT' | 'AI_RECOMMENDATION'
    attachmentName?: string | null
    sectionTitle: string
    location?: string | null
    excerpt: string
  } | null
  companyEvidenceLevel?:
    | 'OFFICIAL_DOCUMENT'
    | 'USER_CONFIRMED'
    | 'OFFICIAL_WEBSITE'
    | 'PUBLIC_INFORMATION'
    | 'UNKNOWN'
    | null
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
