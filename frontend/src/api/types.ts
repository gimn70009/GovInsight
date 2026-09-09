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

export type MonitoringScheduleFrequency = 'DAILY' | 'WEEKDAYS' | 'CUSTOM'
export type Weekday = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'

export interface MonitoringSchedule {
  enabled: boolean
  frequency: MonitoringScheduleFrequency
  executionTime: string
  customDays: Weekday[]
}

export type MonitoringSchedulePayload = MonitoringSchedule

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
    usesDemoProfile?: boolean
    preparationSchemaVersion?: number
    preparation: {
      meetingAgenda: string[]
      eligibilityChecklist: ProposalPreparationItem[]
      submissionDocuments: ProposalPreparationItem[]
      applicationDeadline?: string | null
      strategy: {
        decision?: 'GO' | 'CONDITIONAL_GO' | 'HOLD' | 'NO_GO' | null
        decisionReason?: string | null
        recommendedProject?: string | null
        recommendedParticipation?: string | null
        alternativeParticipation?: string | null
        capabilityMatches?: Array<{ confirmedFact: string; strategicInterpretation: string }> | null
        criticalGaps?: Array<{
          gap: string
          nextAction: string
          owner: string
          targetTiming: string
          workType?: ProposalWorkType | null
          estimatedBusinessDays?: number | null
          targetDate?: string | null
          scheduleBasis?: string | null
        }> | null
        stopCriteria?: Array<{ type: 'OFFICIAL_REQUIREMENT' | 'INTERNAL_RECOMMENDATION'; condition: string; rationale: string }> | null
      }
    } | null
  }
  opportunity: OpportunityAssessment | null
}

export interface ProposalPreparationItem {
  title: string
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
  readinessScore?: number | null
  conditionScore?: number | null
  evidenceScore?: number | null
  scheduleScore?: number | null
  workType?: ProposalWorkType | null
  estimatedBusinessDays?: number | null
  scoreBasis?: string[] | null
}

export type ProposalWorkType =
  | 'INTERNAL_CONFIRMATION'
  | 'EXTERNAL_CONFIRMATION'
  | 'DOCUMENT_ISSUANCE'
  | 'CERTIFICATION'
  | 'SIGNATURE_SEAL'
  | 'BUDGET_REVIEW'
  | 'PROPOSAL_WRITING'
  | 'TECHNICAL_PLANNING'
  | 'DOMESTIC_PARTNER'
  | 'INTERNATIONAL_PARTNER'
  | 'LEGAL_CONTRACT'
  | 'OTHER'

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

export interface SimilarNoticeComparisonSide {
  organizationName: string
  purpose: string
  supportScale: string
  applicationDeadline: string
  eligibility: string
  requiredPartner: string
}

export interface SimilarNoticeResult {
  currentNotice: SimilarNoticeComparisonSide
  similarNotices: Array<{
    detectionId: number
    similarityScore: number | null
    matchBasis?: 'HYBRID' | 'LEXICAL' | 'SEMANTIC' | 'LEXICAL_ONLY'
    title: string
    originalUrl: string
    comparison: SimilarNoticeComparisonSide
    legalReview?: {
      overallStatus: 'HIGH' | 'REVIEW_REQUIRED' | 'RESTRICTION_FOUND' | 'DATA_INSUFFICIENT' | 'ASSESSMENT_INCOMPLETE' | 'NOT_FOUND'
      summary: string
      checks: Array<{
        type: 'DUPLICATE_SUPPORT' | 'COST_DOUBLE_COUNTING' | 'RESULT_IP_REUSE' | 'CONFIDENTIALITY' | 'PROPOSAL_TEXT_REUSE'
        label: string
        status: 'HIGH' | 'REVIEW_REQUIRED' | 'RESTRICTION_FOUND' | 'DATA_INSUFFICIENT' | 'ASSESSMENT_INCOMPLETE' | 'NOT_FOUND'
        finding: string
        evidence: string
      }>
      disclaimer: string
    }
  }>
}

export interface LegalPairResult {
  usesDemoProfile?: boolean
  status: 'COMPLETED' | 'UNAVAILABLE' | 'NEEDS_EVIDENCE'
  message: string
  insights: Array<{
    type: string
    comparison: string
    implication: string
    verification: string
    evidenceIds: number[]
  }>
}

export interface ProposalSource {
  attachmentId: number
  partIndex: number
  fileName: string
  attachmentName: string
  available: boolean
  reason: string
}

export interface ProposalWrittenDraft {
  status: 'COMPLETED' | 'NEEDS_TEMPLATE' | 'UNAVAILABLE'
  fileName: string
  usesDemoProfile: boolean
  message: string
  sections: Array<{
    title: string
    body: string
    sourceQuote: string
    selectionReason: string
    companyEvidence: string[]
    confirmationItems: string[]
  }>
}

export interface ProposalDraftState {
  drafts: SavedProposalDraft[]
  running: Array<{ attachmentId: number; partIndex: number }>
}

export interface SavedProposalDraft {
  attachmentId: number
  partIndex: number
  attachmentName: string
  createdAt: string
  lastViewedAt: string
  result: ProposalWrittenDraft
}

export interface TelegramRecipient { chatId: string; name: string; enabled: boolean }
export interface TelegramSettings {
  version: number | null
  enabled: boolean
  botConfigured: boolean
  recipients: TelegramRecipient[]
  updatedAt: string | null
}
export type TelegramSettingsPayload = Pick<TelegramSettings, 'version' | 'enabled' | 'recipients'>
export interface TelegramConnection {
  botConnected: boolean; chatConnected: boolean
  botName: string | null; botUsername: string | null
  chatTitle: string | null; chatType: string | null
  message: string; checkedAt: string
}
export type TelegramDeliveryStatus = 'SENT' | 'PARTIAL' | 'FAILED' | 'SENDING' | 'NOT_SENT' | 'PREPARING' | 'REPORT_FAILED'
export interface TelegramReport {
  reportId: number; runId: number; title: string | null
  triggerType: 'MANUAL' | 'SCHEDULED'
  createdAt: string; generatedAt: string | null
  status: TelegramDeliveryStatus
  recipientCount: number; sentCount: number; failedCount: number
  errorMessage: string | null
}
export interface TelegramRecipientDelivery {
  deliveryId: number; chatId: string; name: string | null
  status: 'PENDING' | 'SENT' | 'FAILED'
  attemptCount: number; attemptedAt: string | null; sentAt: string | null
  errorMessage: string | null
}
export interface TelegramReportDetail {
  report: TelegramReport
  body: string | null
  deliveries: TelegramRecipientDelivery[]
}
