import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft,
  CalendarDays,
  CircleHelp,
  ChevronDown,
  Download,
  ExternalLink,
  File,
  FileSearch2,
  GitCompareArrows,
  Lightbulb,
  Paperclip,
  RefreshCw,
  RotateCcw,
  Search,
  Sparkles,
  ShieldCheck,
  X,
} from 'lucide-react'
import { api } from '../api/client'
import type { ChangeType, DocumentAnalysis, DocumentDetail, DocumentDetection, MonitoringRun, OpportunityDimensionType, OpportunityPriority, ProposalPreparationItem, SimilarNoticeResult } from '../api/types'
import { Badge, EmptyState, InlineError, Loading, Pagination } from '../components/ui'

const opportunityLabels: Record<OpportunityDimensionType, string> = {
  COMPANY_FIT: '회사 적합도',
  BUSINESS_VALUE: '사업 매력도',
  FEASIBILITY: '실행 가능성',
  URGENCY: '긴급도',
}
const opportunityOrder: OpportunityDimensionType[] = [
  'COMPANY_FIT',
  'BUSINESS_VALUE',
  'FEASIBILITY',
  'URGENCY',
]
const opportunityPriority = {
  HIGH: ['우선 검토', 'danger'],
  NORMAL: ['검토 권장', 'warning'],
  LOW: ['참고', 'neutral'],
} as const

const changeLabel: Record<ChangeType, string> = {
  NEW_DOCUMENT: '신규',
  UPDATED_DOCUMENT: '수정',
  UNCHANGED_DOCUMENT: '변경 없음',
}
const eligibility = {
  ELIGIBLE: ['지원 가능', 'success'],
  INELIGIBLE: ['지원 불가능', 'danger'],
  REVIEW_REQUIRED: ['추가 검토 필요', 'warning'],
} as const
const favorability = {
  FAVORABLE: ['유리한 변경', 'success'],
  UNFAVORABLE: ['불리한 변경', 'danger'],
  NEUTRAL: ['영향 없음', 'neutral'],
  NOT_APPLICABLE: ['비교 대상 없음', 'info'],
  REVIEW_REQUIRED: ['추가 검토 필요', 'warning'],
} as const

const formatDate = (value: string | null) =>
  value
    ? new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value))
    : '게시일 미확인'

const formatDateTime = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))

const formatBytes = (value: number | null) =>
  value == null ? '크기 미확인' : value < 1024 * 1024 ? `${Math.ceil(value / 1024)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`

function getChangeImpact(detail: DocumentDetail, value: NonNullable<DocumentDetail['analysis']>['favorableOrNot']) {
  if (detail.changeType === 'NEW_DOCUMENT') {
    return { label: '비교 대상 없음', tone: 'info', description: '처음 확인된 문서로 이전 버전이 없어요.' }
  }
  if (detail.changeType === 'UNCHANGED_DOCUMENT') {
    return { label: '변경 없음', tone: 'neutral', description: '이전 확인 이후 문서 내용이 달라지지 않았어요.' }
  }
  const [label, tone] = favorability[value]
  return { label, tone, description: '이전 버전과 비교했을 때 회사에 미치는 영향이에요.' }
}

function proposalHeading() {
  return '회사 관점에서 정리했어요'
}

const preparationStatus = {
  READY: ['충족', 'success'],
  VERIFIED: ['충족', 'success'],
  LIKELY: ['확인 필요', 'warning'],
  ACTION_REQUIRED: ['확인 필요', 'warning'],
  NEEDS_CONFIRMATION: ['확인 필요', 'warning'],
  MISSING: ['확인 필요', 'warning'],
  INELIGIBLE: ['충족 불가', 'danger'],
  NOT_APPLICABLE: ['해당 없음', 'neutral'],
} as const
const documentPreparationStatus = {
  READY: ['준비 완료', 'success'],
  VERIFIED: ['준비 완료', 'success'],
  LIKELY: ['확인 필요', 'warning'],
  ACTION_REQUIRED: ['준비 필요', 'warning'],
  NEEDS_CONFIRMATION: ['확인 필요', 'warning'],
  MISSING: ['준비 필요', 'warning'],
  INELIGIBLE: ['준비 필요', 'warning'],
  NOT_APPLICABLE: ['해당 없음', 'neutral'],
} as const
const companyInputStatus = {
  READY: ['확인 완료', 'success'],
  VERIFIED: ['확인 완료', 'success'],
  LIKELY: ['확인 필요', 'warning'],
  ACTION_REQUIRED: ['준비 필요', 'warning'],
  NEEDS_CONFIRMATION: ['확인 필요', 'warning'],
  MISSING: ['준비 필요', 'warning'],
  INELIGIBLE: ['준비 필요', 'warning'],
  NOT_APPLICABLE: ['해당 없음', 'neutral'],
} as const
const strategyDecision = {
  GO: ['지원 권장', 'success'],
  CONDITIONAL_GO: ['조건부 지원 권장', 'warning'],
  HOLD: ['판단 보류', 'warning'],
  NO_GO: ['지원 비권장', 'danger'],
} as const
const strategyProjectHeading = {
  GO: '추천 세부 과제',
  CONDITIONAL_GO: '추천 세부 과제',
  HOLD: '과제 확정 조건',
  NO_GO: '재검토 조건',
} as const

const requirementLevelLabel = {
  MANDATORY: '필수',
  CONDITIONAL: '해당 시',
  OPTIONAL: '선택',
  RECOMMENDED: '내부 권장',
} as const

const requirementStageLabel = {
  APPLICATION: '신청 단계',
  EVALUATION: '평가 단계',
  POST_SELECTION: '선정 이후',
  AGREEMENT: '협약 단계',
  EXECUTION: '수행 단계',
  REPORTING: '결과보고',
} as const

const expiredDeadlinePattern = /(?:마감\s*(?:지남|경과)|접수(?:기한|기간|마감)[^.]{0,40}(?:지났|경과|종료|불가능))/u
const formReferencePattern = /\s*[（(]\s*((?:양식|서식)\s*\d+)\s*[)）]\s*/gu
const readableSentence = (value: string) => value.replace(/\s+·\s+/gu, ', ')
const genericAppliesTo = new Set(['신청기관', '모든 신청기관', '전체 신청기관', '해당 기관', '참여기관'])
const formatEvidenceSource = (parts: Array<string | null | undefined>) => {
  const values = parts.filter((value): value is string => Boolean(value))
  if (values.length === 0) return null
  if (values.length === 1) return `출처는 ${values[0]}입니다.`
  return `출처는 ${values.slice(0, -1).join(', ')}이며 위치는 ${values.at(-1)}입니다.`
}

function isExpiredApplication(detail: DocumentDetail) {
  const urgencyReason = detail.analysis?.opportunity?.dimensions.find(({ type }) => type === 'URGENCY')?.reason ?? ''
  return expiredDeadlinePattern.test(`${urgencyReason} ${detail.analysis?.summary ?? ''}`)
}

function PreparationChecklist({
  items,
  kind,
  sourceAttachmentNames = [],
  applicationExpired = false,
}: {
  items: ProposalPreparationItem[]
  kind: 'eligibility' | 'document' | 'company'
  sourceAttachmentNames?: string[]
  applicationExpired?: boolean
}) {
  return (
    <div className="preparation-checklist">
      {items.filter((item) => item.status !== 'NOT_APPLICABLE').map((item) => {
        const isExpiredDeadline = applicationExpired && /접수|신청|공고.*기한|마감/u.test(item.title)
        const statusMap = kind === 'document'
          ? documentPreparationStatus
          : kind === 'company'
            ? companyInputStatus
            : preparationStatus
        const status = isExpiredDeadline
          ? kind === 'eligibility'
            ? ['충족 불가', 'danger'] as const
            : ['준비 필요', 'warning'] as const
          : statusMap[item.status]
        const formReference = item.title.match(formReferencePattern)?.[0]?.replace(/[（）()]/gu, '').trim()
        const title = item.title.replace(formReferencePattern, ' ').replace(/\s+/gu, ' ').trim()
        const source = formReference && sourceAttachmentNames.length === 1
          ? `출처는 ${sourceAttachmentNames[0]}이며 해당 문서의 ${formReference}입니다.`
          : null
        const requirementLevel = item.requirementLevel ? requirementLevelLabel[item.requirementLevel] : null
        const stage = item.stage ? requirementStageLabel[item.stage] : null
        const appliesTo = item.appliesTo && !genericAppliesTo.has(item.appliesTo)
          ? item.appliesTo
          : null
        const evidenceSource = item.source
          ? formatEvidenceSource([item.source.attachmentName, item.source.sectionTitle, item.source.location])
          : source
        return (
          <article key={item.title}>
            <div className="preparation-checklist__title">
              <strong>{title}</strong>
              <div className="preparation-checklist__score">
                <Badge tone={status[1]}>{status[0]}</Badge>
              </div>
            </div>
            {(requirementLevel || stage || appliesTo) && (
              <div className="preparation-checklist__meta">
                {requirementLevel && <span>{requirementLevel}</span>}
                {stage && <span>{stage}</span>}
                {appliesTo && <span>{appliesTo}</span>}
              </div>
            )}
            <div className="preparation-checklist__action">
              <span>담당자 할 일</span>
              <p>{readableSentence(item.nextAction)}</p>
            </div>
            <details className="preparation-checklist__details">
              <summary>판단 및 근거 보기</summary>
              <div className="preparation-checklist__judgement">
                <span>현재 판단</span>
                <p>{readableSentence(item.detail)}</p>
              </div>
              {(evidenceSource || item.source?.excerpt) && (
                <div className="preparation-checklist__evidence">
                <div>
                  {evidenceSource && <span className="preparation-checklist__source">{evidenceSource}</span>}
                  {item.source?.excerpt && <blockquote>{item.source.excerpt}</blockquote>}
                </div>
                </div>
              )}
            </details>
          </article>
        )
      })}
    </div>
  )
}

const isProposalSummary = (title: string) =>
  /(?:^|[>·\s])(?:요약문|요약서|사업\s*요약|제안\s*요약)(?:$|[>·\s])/u.test(title)

const formatProposalDraftBody = (body: string) =>
  body.replace(
    /['"]?\[회사 확인 필요:\s*(.*?)\]['"]?(?:\s*(?:으로|로)\s*보완하겠습니다)?/gu,
    (_, item: string) => `${item.trim()}에 관한 내용은 회사 내부 검토 후 확정이 필요합니다.`,
  )
  .replace(/\.\s*\./gu, '.')
export default function DocumentsPage() {
  const [documents, setDocuments] = useState<DocumentDetection[]>([])
  const [runs, setRuns] = useState<MonitoringRun[]>([])
  const [selectedRunId, setSelectedRunId] = useState<number | 'ALL' | null>(null)
  const [page, setPage] = useState(0)
  const [pages, setPages] = useState(0)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [priorityFilter, setPriorityFilter] = useState<'ALL' | OpportunityPriority>('ALL')
  const [orderByOpportunityScore, setOrderByOpportunityScore] = useState(false)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [appliedFrom, setAppliedFrom] = useState('')
  const [appliedTo, setAppliedTo] = useState('')
  const [showDateFilter, setShowDateFilter] = useState(false)
  const [selectedId, setSelectedId] = useState<number | null>(null)

  const loadRuns = useCallback(async () => {
    try {
      const data = await api.getRuns(0, 50)
      setRuns(data.content)
      setSelectedRunId((current) => current ?? data.content[0]?.runId ?? 'ALL')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '모니터링 실행 이력을 불러오지 못했습니다.')
      setSelectedRunId('ALL')
    }
  }, [])

  const load = useCallback(async () => {
    if (selectedRunId === null) return
    setLoading(true)
    setError('')
    try {
      const data = await api.getDocuments(
        page,
        20,
        appliedFrom || undefined,
        appliedTo || undefined,
        selectedRunId === 'ALL' ? undefined : selectedRunId,
        orderByOpportunityScore ? 'OPPORTUNITY_SCORE' : 'LATEST',
      )
      setDocuments(data.content)
      setPages(data.totalPages)
      setTotal(data.totalElements)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '감지 문서를 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }, [page, appliedFrom, appliedTo, selectedRunId, orderByOpportunityScore])

  useEffect(() => {
    void loadRuns()
  }, [loadRuns])

  useEffect(() => {
    void load()
  }, [load])

  const filtered = useMemo(
    () =>
      documents.filter((item) => {
        const matchesQuery = `${item.organizationName} ${item.boardName} ${item.title}`
          .toLowerCase()
          .includes(query.toLowerCase())
        return matchesQuery && (priorityFilter === 'ALL' || item.opportunityPriority === priorityFilter)
      }),
    [documents, query, priorityFilter],
  )

  const applyDateRange = () => {
    if (from && to && from > to) {
      setError('시작 일시는 종료 일시보다 늦을 수 없습니다.')
      return
    }
    setError('')
    setPage(0)
    setAppliedFrom(from)
    setAppliedTo(to)
    setShowDateFilter(false)
  }

  const resetDateRange = () => {
    setFrom('')
    setTo('')
    setPage(0)
    setAppliedFrom('')
    setAppliedTo('')
    setShowDateFilter(false)
  }

  const selectedRun = selectedRunId === 'ALL' ? null : runs.find((run) => run.runId === selectedRunId)
  const dateFilterLabel = appliedFrom || appliedTo
    ? `${appliedFrom ? appliedFrom.slice(5).replace('T', ' ') : '처음'} – ${appliedTo ? appliedTo.slice(5).replace('T', ' ') : '현재'}`
    : '기간 설정'

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">DOCUMENTS</span>
          <h1>감지된 게시글</h1>
          <p>새로 올라오거나 달라진 공고를 확인하고, 우리 회사에 필요한 내용을 살펴보세요.</p>
        </div>
        <div className="header-stat">
          <small>확인된 문서</small>
          <strong>{total.toLocaleString()}<em>건</em></strong>
        </div>
      </header>

      {error && <InlineError message={error} />}

      <section className="panel documents-panel">
        <div className="run-filter-bar">
          <div className="run-filter-bar__copy">
            <span>표시할 실행</span>
            <strong>{selectedRunId === 'ALL' ? '전체 실행 결과' : '최근 실행 결과'}</strong>
            <small>
              {selectedRun
                ? `${formatDateTime(selectedRun.requestedAt)} · ${selectedRun.totalSourceCount}개 소스 · ${selectedRun.detectedDocumentCount}건`
                : '모든 실행에서 감지한 게시글을 함께 보여드려요.'}
            </small>
          </div>
          <label className="run-select">
            <select
              value={selectedRunId ?? ''}
              onChange={(event) => {
                setPage(0)
                setSelectedRunId(event.target.value === 'ALL' ? 'ALL' : Number(event.target.value))
              }}
              disabled={selectedRunId === null}
            >
              <option value="ALL">전체 실행 결과</option>
              {runs[0] && (
                <option value={runs[0].runId}>
                  최근 실행 결과 · {formatDateTime(runs[0].requestedAt)} · {runs[0].detectedDocumentCount}건
                </option>
              )}
            </select>
            <ChevronDown size={15} />
          </label>
        </div>
        <div className="panel-header document-filter-header">
          <div className="document-filter-header__filters">
            <div className="filter-tabs">
              {(['ALL', 'HIGH', 'NORMAL', 'LOW'] as const).map((value) => (
                <button
                  key={value}
                  className={priorityFilter === value ? 'active' : ''}
                  onClick={() => setPriorityFilter(value)}
                >
                  {value === 'ALL' ? '전체' : opportunityPriority[value][0]}
                </button>
              ))}
            </div>
            <label className="score-sort-option">
              <input
                type="checkbox"
                checked={orderByOpportunityScore}
                onChange={(event) => {
                  setPage(0)
                  setOrderByOpportunityScore(event.target.checked)
                }}
              />
              <span>기회 점수 높은 순</span>
            </label>
          </div>
          <div className="panel-actions">
            <div className="date-filter-menu">
              <button
                className={`date-filter-trigger${appliedFrom || appliedTo ? ' active' : ''}`}
                onClick={() => setShowDateFilter((visible) => !visible)}
                aria-expanded={showDateFilter}
              >
                <CalendarDays size={15} />
                <span>{dateFilterLabel}</span>
                <ChevronDown size={14} />
              </button>
              {showDateFilter && (
                <div className="date-filter-popover">
                  <div className="date-filter-popover__header">
                    <strong>확인 기간 설정</strong>
                    <small>선택한 실행 안에서 확인 일시로 조회해요.</small>
                  </div>
                  <label>
                    <span>시작 일시</span>
                    <input type="datetime-local" value={from} onChange={(event) => setFrom(event.target.value)} />
                  </label>
                  <label>
                    <span>종료 일시</span>
                    <input type="datetime-local" value={to} onChange={(event) => setTo(event.target.value)} />
                  </label>
                  <div className="date-filter-popover__actions">
                    <button className="button button--subtle" onClick={resetDateRange}>
                      <RotateCcw size={14} /> 초기화
                    </button>
                    <button className="button button--primary" onClick={applyDateRange}>적용</button>
                  </div>
                </div>
              )}
            </div>
            <label className="search-box">
              <Search size={17} />
              <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="기관, 게시판, 제목 검색" />
            </label>
            <button className="icon-button" onClick={() => void load()} title="새로고침">
              <RefreshCw size={17} />
            </button>
          </div>
        </div>

        {loading ? (
          <Loading />
        ) : filtered.length === 0 ? (
          <EmptyState
            icon={<FileSearch2 />}
            title="조건에 맞는 게시글이 없어요"
            description="검색어, 대응 우선순위 또는 확인 일시를 바꿔 다시 확인해 보세요."
          />
        ) : (
          <div className="document-list">
            {filtered.map((item) => (
              <button className="document-row" key={item.detectionId} onClick={() => setSelectedId(item.detectionId)}>
                <div className="document-row__org">
                  <span className="source-logo">{item.organizationName.slice(0, 1)}</span>
                  <span><strong>{item.organizationName}</strong><small>{item.boardName}</small></span>
                </div>
                <div className="document-row__title">
                  <strong>{item.title}</strong>
                  <span>
                    <Badge tone={item.changeType === 'UPDATED_DOCUMENT' ? 'warning' : item.changeType === 'NEW_DOCUMENT' ? 'info' : 'neutral'}>
                      {changeLabel[item.changeType]}
                    </Badge>
                    <small><Paperclip size={13} />{item.attachmentCount}개</small>
                  </span>
                </div>
                <div className="document-row__importance">
                  {item.opportunityPriority ? (
                    <>
                      <Badge tone={opportunityPriority[item.opportunityPriority][1]}>
                        {opportunityPriority[item.opportunityPriority][0]}
                      </Badge>
                      {item.opportunityScore != null && <span className="document-row__score">{item.opportunityScore}점</span>}
                    </>
                  ) : <Badge>분석 중</Badge>}
                </div>
                <time>{formatDateTime(item.lastCheckedAt)}</time>
              </button>
            ))}
          </div>
        )}
        <Pagination page={page} totalPages={pages} onChange={setPage} />
      </section>

      {selectedId && <DocumentDrawer detectionId={selectedId} onClose={() => setSelectedId(null)} />}
    </div>
  )
}

function DocumentDrawer({ detectionId, onClose }: { detectionId: number; onClose: () => void }) {
  const [detail, setDetail] = useState<DocumentDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [similarNotices, setSimilarNotices] = useState<SimilarNoticeResult | null>(null)
  const [similarLoading, setSimilarLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    api.getDocument(detectionId)
      .then(setDetail)
      .catch((cause) => setError(cause instanceof Error ? cause.message : '상세 내용을 불러오지 못했습니다.'))
      .finally(() => setLoading(false))
  }, [detectionId])

  useEffect(() => {
    if (!detail?.analysis) return
    setSimilarLoading(true)
    api.getSimilarNotices(detectionId)
      .then(setSimilarNotices)
      .catch(() => setSimilarNotices(null))
      .finally(() => setSimilarLoading(false))
  }, [detectionId, Boolean(detail?.analysis)])

  useEffect(() => {
    if (detail?.analysis?.proposal.draftStatus !== 'GENERATING') return
    const timer = window.setInterval(() => {
      api.getDocument(detectionId).then(setDetail).catch(() => undefined)
    }, 3000)
    return () => window.clearInterval(timer)
  }, [detectionId, detail?.analysis?.proposal.draftStatus])

  return (
    <div className="drawer-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <aside className="drawer drawer--detail">
        <div className="drawer__top">
          <button className="button button--subtle" onClick={onClose}><ArrowLeft size={17} />목록으로</button>
          {detail && <a className="button button--secondary" href={detail.originalUrl} target="_blank" rel="noreferrer">원문 보기<ExternalLink size={16} /></a>}
        </div>
        {loading ? <Loading label="문서 내용을 정리하고 있어요" /> : error ? <InlineError message={error} /> : detail && <DocumentContent detail={detail} similarNotices={similarNotices} similarLoading={similarLoading} />}
      </aside>
    </div>
  )
}

function OpportunityScore({ opportunity }: { opportunity: NonNullable<DocumentAnalysis['opportunity']> }) {
  const [showScoreGuide, setShowScoreGuide] = useState(false)
  const dimensions = opportunityOrder
    .map((type) => opportunity.dimensions.find((dimension) => dimension.type === type))
    .filter((dimension): dimension is NonNullable<typeof dimension> => Boolean(dimension))
  const priority = opportunityPriority[opportunity.priority]

  return (
    <section className="detail-section opportunity-card">
      <div className="opportunity-card__header">
        <div>
          <small>AI OPPORTUNITY SCORE</small>
          <h3>우리 회사의 기회 분석</h3>
          <p>공고 내용과 공개된 회사 정보를 기준으로 평가했어요.</p>
        </div>
        <div className="opportunity-total">
          <strong>{opportunity.totalScore}</strong><span>/100</span>
          <Badge tone={priority[1]}>{priority[0]}</Badge>
        </div>
      </div>

      <button
        type="button"
        className={`score-guide-toggle${showScoreGuide ? ' is-open' : ''}`}
        onClick={() => setShowScoreGuide((current) => !current)}
        aria-expanded={showScoreGuide}
      >
        <CircleHelp size={15} />
        기회 점수는 어떻게 계산하나요?
        <ChevronDown size={15} />
      </button>
      {showScoreGuide && (
        <div className="score-guide">
          <dl>
            <div><dt>회사 적합도</dt><dd>50%</dd></div>
            <div><dt>사업 매력도</dt><dd>20%</dd></div>
            <div><dt>실행 가능성</dt><dd>20%</dd></div>
            <div><dt>긴급도</dt><dd>10%</dd></div>
          </dl>
          <p>화면에는 사업 기회 판단에 직접 사용하는 네 지표를 보여드려요. 회사 적합도가 40점 이하이면 종합 점수는 최대 49점이에요.</p>
        </div>
      )}

      <div className="opportunity-bars">
        {dimensions.map((dimension) => (
          <div className="opportunity-row" key={dimension.type}>
            <div className="opportunity-row__label">
              <strong>{opportunityLabels[dimension.type]}</strong>
              <span>{dimension.score}</span>
            </div>
            <div className="opportunity-track"><i style={{ width: `${dimension.score}%` }} /></div>
            <p>{dimension.reason}</p>
          </div>
        ))}
      </div>
    </section>
  )
}

function DocumentContent({ detail, similarNotices, similarLoading }: { detail: DocumentDetail; similarNotices: SimilarNoticeResult | null; similarLoading: boolean }) {
  const [activeDetailTab, setActiveDetailTab] = useState<'ANALYSIS' | 'PROPOSAL' | 'SIMILAR'>('ANALYSIS')
  const analysis = detail.analysis
  const applicationExpired = isExpiredApplication(detail)
  const eligible = analysis
    ? applicationExpired ? ['지원 불가능', 'danger'] as const : eligibility[analysis.eligibility]
    : null
  const changeImpact = analysis ? getChangeImpact(detail, analysis.favorableOrNot) : null
  const proposalSections = analysis?.proposal.sections ?? []
  const proposalDraft = analysis?.proposal
  const showProposalTab = Boolean(proposalDraft)
  const proposalSummary = proposalDraft?.draftSections.find((section) => isProposalSummary(section.title))
  const proposalWritingSections = proposalDraft?.draftSections.filter((section) => !isProposalSummary(section.title)) ?? []
  const proposalUnavailableReason = analysis && proposalDraft
    ? getProposalUnavailableReason(detail, analysis)
    : ''
  const applicationDocuments = proposalDraft?.preparation?.submissionDocuments.filter((item) => !item.stage || item.stage === 'APPLICATION') ?? []
  const laterDocuments = proposalDraft?.preparation?.submissionDocuments.filter((item) => item.stage && item.stage !== 'APPLICATION') ?? []
  const pendingPreparationItems = (items: ProposalPreparationItem[]) => items.filter((item) => item.status !== 'NOT_APPLICABLE' && !['READY', 'VERIFIED'].includes(item.status))
  const preparation = proposalDraft?.preparation
  const proposalSectionsOverview = preparation ? [
    { id: 'proposal-agenda', label: '회의 안건', count: preparation.meetingAgenda.length, suffix: '건' },
    { id: 'proposal-eligibility', label: '확인할 지원 조건', count: pendingPreparationItems(preparation.eligibilityChecklist).length, suffix: '건' },
    { id: 'proposal-documents', label: '준비할 제출 서류', count: pendingPreparationItems(preparation.submissionDocuments).length, suffix: '건' },
    { id: 'proposal-company', label: '회사 확인 정보', count: pendingPreparationItems(preparation.companyInputs).length, suffix: '건' },
  ] : []

  useEffect(() => {
    setActiveDetailTab('ANALYSIS')
  }, [detail.detectionId])


  return (
    <div className="document-detail">
      <header className="document-detail__header">
        <div className="document-kicker"><span>{detail.organizationName}</span><i />{detail.boardName}</div>
        <h2>{detail.title}</h2>
        <div className="document-meta">
          <span><CalendarDays size={15} />{formatDate(detail.publishedAt)}</span>
          <Badge tone={detail.changeType === 'UPDATED_DOCUMENT' ? 'warning' : detail.changeType === 'NEW_DOCUMENT' ? 'info' : 'neutral'}>{changeLabel[detail.changeType]}</Badge>
          <span>마지막 확인 {formatDateTime(detail.lastCheckedAt)}</span>
        </div>
      </header>

      {analysis && (
        <nav className="detail-tabs" aria-label="문서 상세 보기">
          <button className={activeDetailTab === 'ANALYSIS' ? 'active' : ''} onClick={() => setActiveDetailTab('ANALYSIS')}>공고 분석</button>
          {showProposalTab && <button className={activeDetailTab === 'PROPOSAL' ? 'active' : ''} onClick={() => setActiveDetailTab('PROPOSAL')}>사업 제안</button>}
          <button className={activeDetailTab === 'SIMILAR' ? 'active' : ''} onClick={() => setActiveDetailTab('SIMILAR')}>유사 공고 비교{!similarLoading && ` ${similarNotices?.similarNotices.length ?? 0}`}</button>
        </nav>
      )}

      {analysis ? (
        <>
          {activeDetailTab === 'ANALYSIS' ? (
          <>
          <section className="analysis-hero">
            <div className="analysis-hero__title">
              <span><Sparkles size={18} /></span>
              <div><small>AI ANALYSIS</small><h3>이 문서, 이렇게 이해하면 돼요</h3></div>
            </div>
            <p className="summary-text">{analysis.summary}</p>
          </section>
          <section className="detail-section">
            <h3>핵심 내용</h3>
            <ul className="key-points">
              {analysis.keyPoints.map((point, index) => <li key={`${point}-${index}`}><span>{index + 1}</span><p>{point}</p></li>)}
            </ul>
          </section>
          {analysis.opportunity && <OpportunityScore opportunity={analysis.opportunity} />}
          <div className="insight-grid">
            <section className="insight-card">
              <small>지원 가능성</small>
              <div><Badge tone={eligible![1]}>{eligible![0]}</Badge></div>
              <p>공개된 회사 정보와 공고 조건을 기준으로 판단했어요.</p>
            </section>
            <section className="insight-card">
              <small>변경 영향</small>
              <div><Badge tone={changeImpact!.tone}>{changeImpact!.label}</Badge></div>
              <p>{changeImpact!.description}</p>
            </section>
          </div>
          <section className="detail-section reason-box"><h3>이렇게 판단했어요</h3><p>{analysis.reason}</p></section>
          <section className="detail-section proposal-box">
            <span className="proposal-box__icon"><Sparkles size={18} /></span>
            <div className="proposal-box__content">
              <h3>{proposalHeading()}</h3>
              <div className="proposal-steps">
                {proposalSections.map((section, index) => (
                  <article className="proposal-step" key={`${section.title ?? 'insight'}-${index}`}>
                    <span>{index + 1}</span>
                    <div>
                      <h4>{section.title}</h4>
                      <p>{section.body}</p>
                    </div>
                  </article>
                ))}
              </div>
            </div>
          </section>
          </>
          ) : activeDetailTab === 'PROPOSAL' && proposalDraft ? (
            <section className="detail-section proposal-draft-panel">
              {proposalDraft.preparation ? (
                <div className="proposal-preparation">
                  <nav className="proposal-section-nav" aria-label="사업 제안 섹션 바로가기">
                    <div className="proposal-section-nav__title"><strong>준비 현황</strong><span>확인할 영역을 선택하면 해당 항목으로 이동합니다.</span></div>
                    {proposalSectionsOverview.map((section) => <a href={`#${section.id}`} key={section.id}><span>{section.label}</span><strong>{section.count}<small>{section.suffix}</small></strong></a>)}
                  </nav>
                  <details className="preparation-block preparation-block--collapsible" id="proposal-agenda">
                    <summary className="preparation-block__header"><span>02</span><div><h4>회의 안건</h4><p>제안서 작성 전에 관계자들과 먼저 결정할 내용입니다.</p></div><em>{proposalDraft.preparation.meetingAgenda.length}건 <ChevronDown size={16} /></em></summary>
                    <ol className="meeting-agenda">
                      {proposalDraft.preparation.meetingAgenda.map((agenda) => <li key={agenda}>{agenda}</li>)}
                    </ol>
                  </details>
                  <details className="preparation-block preparation-block--collapsible" id="proposal-eligibility">
                    <summary className="preparation-block__header"><span>03</span><div><h4>지원 조건 체크리스트</h4><p>공고 요구사항과 현재 확인된 회사 상태를 비교합니다.</p></div><em>{pendingPreparationItems(proposalDraft.preparation.eligibilityChecklist).length}건 <ChevronDown size={16} /></em></summary>
                    <PreparationChecklist kind="eligibility" items={proposalDraft.preparation.eligibilityChecklist} applicationExpired={applicationExpired} />
                  </details>
                  <details className="preparation-block preparation-block--collapsible" id="proposal-documents">
                    <summary className="preparation-block__header"><span>04</span><div><h4>제출 서류 체크리스트</h4><p>접수 전에 확보하거나 새로 작성해야 하는 자료입니다.</p></div><em>{pendingPreparationItems(proposalDraft.preparation.submissionDocuments).length}건 <ChevronDown size={16} /></em></summary>
                    <PreparationChecklist kind="document" items={applicationDocuments} sourceAttachmentNames={proposalDraft.sourceAttachmentNames} applicationExpired={applicationExpired} />
                    {laterDocuments.length > 0 && (
                      <div className="later-requirements">
                        <h5>선정 이후 준비사항</h5>
                        <p>신청서류와 구분해 선정·협약 이후 필요한 자료를 보여드립니다.</p>
                        <PreparationChecklist kind="document" items={laterDocuments} sourceAttachmentNames={proposalDraft.sourceAttachmentNames} />
                      </div>
                    )}
                  </details>
                  <details className="preparation-block preparation-block--collapsible" id="proposal-company">
                    <summary className="preparation-block__header"><span>05</span><div><h4>회사에서 확인·준비할 정보</h4><p>공고만으로 확인할 수 없어 회사 담당자가 직접 확인하거나 작성해야 하는 항목입니다.</p></div><em>{pendingPreparationItems(proposalDraft.preparation.companyInputs).length}건 <ChevronDown size={16} /></em></summary>
                    <PreparationChecklist kind="company" items={proposalDraft.preparation.companyInputs} />
                  </details>
                  <section className="preparation-block strategy-one-page" id="proposal-strategy">
                    <div className="preparation-block__header"><span>01</span><div><h4>제안 전략 한 장 <em className="ai-recommendation-label">AI 제안</em></h4><p>공고 근거와 회사 정보를 바탕으로 제안한 방향이며 담당자 확정이 필요합니다.</p></div></div>
                    {proposalDraft.preparation.strategy.decision ? (
                      <>
                        <div className="strategy-decision">
                          <Badge tone={strategyDecision[proposalDraft.preparation.strategy.decision][1]}>{strategyDecision[proposalDraft.preparation.strategy.decision][0]}</Badge>
                          <p>{proposalDraft.preparation.strategy.decisionReason}</p>
                        </div>
                        <div className="strategy-project">
                          <small>{strategyProjectHeading[proposalDraft.preparation.strategy.decision]}</small>
                          <h5>{proposalDraft.preparation.strategy.recommendedProject}</h5>
                        </div>
                        <div className="strategy-roles">
                          <div><strong>추천 참여 방식</strong><p>{proposalDraft.preparation.strategy.recommendedParticipation}</p></div>
                          <div><strong>조건 미충족 시 대안</strong><p>{proposalDraft.preparation.strategy.alternativeParticipation}</p></div>
                        </div>
                        <div className="strategy-gaps">
                          <div className="strategy-gaps__header">
                            <h6>우선 조치 항목</h6>
                            <p>신청을 진행하기 위해 우선 처리해야 할 미확인·미완료 항목입니다.</p>
                          </div>
                          {proposalDraft.preparation.strategy.criticalGaps?.map((item) => (
                            <article key={item.gap}>
                              <strong>{readableSentence(item.gap)}</strong>
                              <p>{readableSentence(item.nextAction)}</p>
                              <div className="strategy-gap-meta">
                                <span><b>담당 부서</b>{item.owner}</span>
                                <span className="strategy-gap-deadline">
                                  <b>내부 완료 목표일</b>
                                  {item.targetDate ?? readableSentence(item.targetTiming)}
                                  {item.scheduleBasis && (
                                    <span className="strategy-gap-tooltip">
                                      <button type="button" aria-label="내부 완료 목표일 계산 근거">
                                        <CircleHelp size={12} aria-hidden="true" />
                                      </button>
                                      <span role="tooltip">{item.scheduleBasis}</span>
                                    </span>
                                  )}
                                </span>
                              </div>
                            </article>
                          ))}
                        </div>
                        <details className="strategy-supporting-details">
                          <summary>회사 역량과 지원 중단 기준 보기 <ChevronDown size={15} /></summary>
                          <div className="strategy-capabilities">
                            <h6>공고와 연결되는 회사 역량</h6>
                            {proposalDraft.preparation.strategy.capabilityMatches?.map((item) => (
                              <div key={`${item.confirmedFact}-${item.strategicInterpretation}`}><p><span>확인된 사실</span>{item.confirmedFact}</p><p><span>AI 해석</span>{item.strategicInterpretation}</p></div>
                            ))}
                          </div>
                          <div className="strategy-stop">
                            <h6>지원 중단 기준</h6>
                            {proposalDraft.preparation.strategy.stopCriteria?.map((item) => (
                              <article key={item.condition}><span>{item.type === 'OFFICIAL_REQUIREMENT' ? '공고 필수조건' : 'AI 내부 권장 기준'}</span><strong>{item.condition}</strong><p>{item.rationale}</p></article>
                            ))}
                          </div>
                        </details>
                      </>
                    ) : (
                      <p className="strategy-upgrade-notice">다음 모니터링 실행에서 참여 의사결정 중심의 새 전략으로 갱신됩니다.</p>
                    )}
                  </section>
                </div>
              ) : proposalDraft.draftSections.length > 0 ? (
                <>
                  {proposalSummary && (
                    <article className="proposal-executive-summary">
                      <small>PROPOSAL SUMMARY</small>
                      <h4>제안 요약</h4>
                      <p>{formatProposalDraftBody(proposalSummary.body)}</p>
                    </article>
                  )}
                  {proposalWritingSections.length > 0 && (
                    <div className="proposal-draft-sections">
                      {proposalWritingSections.map((section, index) => (
                        <article key={`${section.title}-${index}`}><span>{index + 1}</span><div><h4>{section.title}</h4><p>{formatProposalDraftBody(section.body)}</p></div></article>
                      ))}
                    </div>
                  )}
                </>
              ) : (
                <p className="proposal-draft-empty">
                  {applicationExpired
                    ? '접수기한이 지나 제안 준비안을 생성하지 않았습니다. 향후 재공고 여부를 확인해 주세요.'
                    : proposalDraft.draftStatus === 'GENERATING'
                      ? '공고 분석은 완료되었습니다. 사업 제안을 별도로 준비하고 있으며 완료되면 이 화면이 자동으로 갱신됩니다.'
                      : proposalUnavailableReason}
                </p>
              )}
            </section>
          ) : activeDetailTab === 'SIMILAR' ? (
            <SimilarNoticeComparison result={similarNotices} loading={similarLoading} currentTitle={detail.title} currentOriginalUrl={detail.originalUrl} />
          ) : null}
        </>
      ) : (
        <EmptyState icon={<Sparkles />} title="AI 분석을 준비하고 있어요" description="분석이 완료되면 핵심 내용과 대응 방향을 여기에서 확인할 수 있어요." />
      )}

      {activeDetailTab === 'ANALYSIS' && <section className="detail-section attachments">
        <div className="section-title-row"><h3>첨부파일</h3><Badge>{detail.attachments.length}개</Badge></div>
        {detail.attachments.length === 0 ? (
          <p className="muted">첨부된 파일이 없습니다.</p>
        ) : (
          detail.attachments.map((file) => (
            <a key={file.downloadUrl} href={file.downloadUrl} target="_blank" rel="noreferrer" className="attachment-row">
              <span className="file-icon"><File size={18} /></span>
              <span>
                <strong>{file.fileName}</strong>
                <small>
                  {file.fileExtension.toUpperCase()} · {formatBytes(file.fileSize)}
                  {file.parseStatus !== 'FAILED' && ` · ${file.parseStatus === 'COMPLETED' ? '내용 분석 완료' : '내용 분석 미지원'}`}
                  {file.parseStatus === 'FAILED' && (
                    <span
                      className="attachment-failure-badge"
                      title={file.fileSize == null ? '첨부파일을 다운로드하지 못했어요' : '첨부파일 내용을 읽지 못했어요'}
                    >
                      {file.fileSize == null ? '다운로드 실패' : '읽기 실패'}
                    </span>
                  )}
                </small>
              </span>
              <Download size={18} />
            </a>
          ))
        )}
      </section>}
    </div>
  )
}

function SimilarNoticeComparison({ result, loading, currentTitle, currentOriginalUrl }: { result: SimilarNoticeResult | null; loading: boolean; currentTitle: string; currentOriginalUrl: string }) {
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [expandedCell, setExpandedCell] = useState<{ title: string; content: string } | null>(null)
  useEffect(() => { setSelectedIndex(0) }, [result])

  if (loading) return <Loading label="기존 공고와 사업 목적과 수행 내용을 비교하고 있어요" />
  if (!result || result.similarNotices.length === 0) {
    return (
      <section className="detail-section similar-notice-empty">
        <span><Search size={22} /></span>
        <h3>충분히 유사한 공고가 없습니다</h3>
        <p>제목이나 일부 일반 단어만 비슷한 공고는 비교 대상에서 제외했어요.</p>
      </section>
    )
  }

  const selected = result.similarNotices[Math.min(selectedIndex, result.similarNotices.length - 1)]
  const legalReview = selected.legalReview ?? {
    overallStatus: 'REVIEW_REQUIRED' as const,
    summary: '현재 저장된 비교 결과에는 법률 위험 분석이 없어 추가 확인이 필요합니다.',
    checks: [
      ['DUPLICATE_SUPPORT', '중복지원', '두 공고 담당기관에 동일·유사 과제의 중복 신청 가능 여부를 확인합니다.'],
      ['COST_DOUBLE_COUNTING', '사업비·인건비 중복계상', '동일 인력·기간·비용이 두 과제에 중복 계상되는지 확인합니다.'],
      ['RESULT_IP_REUSE', '성과물·지식재산 재사용', '기존 성과물의 소유권·사용권과 신규성 요구를 확인합니다.'],
      ['CONFIDENTIALITY', '비밀정보·영업비밀', '기존 협약과 비공개 자료의 사용 권한을 확인합니다.'],
      ['PROPOSAL_TEXT_REUSE', '제안서 문장·자료 재사용', '기존 제안서 문장·표·도표의 재사용 허용 범위를 확인합니다.'],
    ].map(([type, label, action]) => ({
      type,
      label,
      status: 'REVIEW_REQUIRED' as const,
      finding: '원문 기반 분석 결과를 아직 확인하지 못했습니다.',
      evidence: '',
      action,
    })),
    disclaimer: '공고 원문 기반의 사전 위험 점검이며 법률 자문이 아닙니다. 최종 신청 전 공고 담당기관과 법무·재무 담당자의 확인이 필요합니다.',
  }
  const rows = [
    { label: '기관', current: result.currentNotice.organizationName, similar: selected.comparison.organizationName },
    { label: '사업 목적', current: result.currentNotice.purpose, similar: selected.comparison.purpose },
    { label: '지원 규모', current: result.currentNotice.supportScale, similar: selected.comparison.supportScale },
    { label: '접수 마감', current: result.currentNotice.applicationDeadline, similar: selected.comparison.applicationDeadline },
    { label: '신청 자격', current: result.currentNotice.eligibility, similar: selected.comparison.eligibility },
    { label: '필수 파트너', current: result.currentNotice.requiredPartner, similar: selected.comparison.requiredPartner },
  ]

  return (
    <section className="detail-section similar-notice-panel">
      <div className="similar-notice-panel__header">
        <div><small>SIMILAR NOTICE</small><h3>유사 공고 비교</h3><p>사업 목적과 수행 내용이 모두 충분히 유사한 공고만 표시해요.</p></div>
        <Badge tone="info">{selected.similarityScore >= 88 ? '의미 유사도 매우 높음' : '의미 유사도 높음'}</Badge>
      </div>
      {result.similarNotices.length > 1 && (
        <label className="similar-notice-select"><span>비교할 공고</span><select value={selectedIndex} onChange={(event) => setSelectedIndex(Number(event.target.value))}>{result.similarNotices.map((notice, index) => <option key={notice.detectionId} value={index}>{notice.title}</option>)}</select></label>
      )}
      <div className="similar-comparison-table-wrap">
        <table className="similar-comparison-table">
          <thead><tr><th>비교 항목</th><th><ComparisonNoticeHeader label="현재 공고" title={currentTitle} url={currentOriginalUrl} /></th><th><ComparisonNoticeHeader label="유사 공고" title={selected.title} url={selected.originalUrl} /></th></tr></thead>
          <tbody>{rows.map((row) => <tr key={row.label}><th>{row.label}</th><td><ExpandableComparisonCell content={row.current} onExpand={() => setExpandedCell({ title: ['현재 공고', row.label].join(' · '), content: row.current })} /></td><td><ExpandableComparisonCell content={row.similar} onExpand={() => setExpandedCell({ title: ['유사 공고', row.label].join(' · '), content: row.similar })} /></td></tr>)}</tbody>
        </table>
      </div>
      <div className="similar-notice-insights">
        <article className="similar-insight similar-insight--reason"><span><GitCompareArrows size={18} /></span><div><strong>유사한 이유</strong><p>{similarityReason(selected.commonPoints)}</p></div></article>
        <article className="similar-insight similar-insight--reuse"><span><Lightbulb size={18} /></span><div><strong>활용 포인트</strong><p>{selected.proposalReuse}</p></div></article>
      </div>
      <section className="legal-review">
        <header><span><ShieldCheck size={19} /></span><div><small>LEGAL RISK CHECK</small><h4>중복·재사용 위험 점검</h4></div></header>
        <p className={`legal-review__overview${legalReview.overallStatus === 'HIGH' ? ' legal-review__overview--high' : ''}`}>{legalReview.summary}</p>
        <div className="legal-review__check-list">{legalReview.checks.map((check) => {
          const findings = splitLegalComparison(check.finding)
          const evidence = splitLegalComparison(check.evidence)
          return <details key={check.type}><summary><strong>{check.label}</strong><span aria-hidden="true"><ChevronDown size={15} /></span></summary><div className="legal-review__detail"><div className="legal-review__sources"><section><small>현재 공고</small><p>{findings.current}</p>{evidence.current && <blockquote><b>원문 근거</b>{evidence.current}</blockquote>}</section><section><small>유사 공고</small><p>{findings.similar}</p>{evidence.similar && <blockquote><b>원문 근거</b>{evidence.similar}</blockquote>}</section></div><div className="legal-review__action"><strong>신청 전 확인</strong><p>{check.action}</p></div></div></details>
        })}</div>
        <p className="legal-review__disclaimer">{legalReview.disclaimer}</p>
      </section>
      {expandedCell && <ComparisonCellDialog title={expandedCell.title} content={expandedCell.content} onClose={() => setExpandedCell(null)} />}
    </section>
  )
}

function ComparisonNoticeHeader({ label, title, url }: { label: string; title: string; url: string }) {
  return <div className="comparison-notice-header"><small>{label}</small><strong>{title}</strong><a href={url} target="_blank" rel="noreferrer">원문 보기<ExternalLink size={13} /></a></div>
}

function ExpandableComparisonCell({ content, onExpand }: { content: string; onExpand: () => void }) {
  const contentRef = useRef<HTMLParagraphElement>(null)
  const [overflowing, setOverflowing] = useState(false)

  useEffect(() => {
    const element = contentRef.current
    if (!element) return
    const measure = () => setOverflowing(element.scrollHeight > element.clientHeight + 1)
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [content])

  return <div className="comparison-cell"><p ref={contentRef}>{content}</p>{overflowing && <button type="button" onClick={onExpand}>전체 보기</button>}</div>
}

function ComparisonCellDialog({ title, content, onClose }: { title: string; content: string; onClose: () => void }) {
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [onClose])

  return <div className="comparison-dialog-backdrop" role="presentation" onMouseDown={onClose}><section className="comparison-dialog" role="dialog" aria-modal="true" aria-labelledby="comparison-dialog-title" onMouseDown={(event) => event.stopPropagation()}><header><div><small>전체 내용</small><h3 id="comparison-dialog-title">{title}</h3></div><button type="button" aria-label="닫기" onClick={onClose}><X size={20} /></button></header><div className="comparison-dialog__content">{content}</div><footer><button type="button" className="button button--primary" onClick={onClose}>확인</button></footer></section></div>
}

function splitLegalComparison(value: string) {
  const normalized = value.trim().replace(/[“”]/g, '')
  if (!normalized) return { current: '', similar: '' }
  const currentPrefix = '현재 공고:'
  const similarPrefix = '유사 공고:'
  const similarIndex = normalized.indexOf(similarPrefix)
  if (normalized.startsWith(currentPrefix) && similarIndex >= 0) {
    return {
      current: normalized.slice(currentPrefix.length, similarIndex).trim(),
      similar: normalized.slice(similarIndex + similarPrefix.length).trim(),
    }
  }
  if (normalized.startsWith(currentPrefix)) {
    return { current: normalized.slice(currentPrefix.length).trim(), similar: '' }
  }
  if (normalized.startsWith(similarPrefix)) {
    return { current: '', similar: normalized.slice(similarPrefix.length).trim() }
  }
  return { current: normalized, similar: '' }
}
function similarityReason(commonPoints: string) {
  const exposesInternalProfile = /proposal|request|business|notice|공고명|핵심어는|유형[, ]/i.test(commonPoints)
  if (!commonPoints || exposesInternalProfile) {
    return '두 공고는 사업 목적과 수행 방식이 유사하며 같은 유형의 지원사업으로 분류됐습니다.'
  }
  return commonPoints
}

function getProposalUnavailableReason(detail: DocumentDetail, analysis: DocumentAnalysis) {
  const proposal = analysis.proposal
  if (proposal.documentType !== 'PROPOSAL_REQUEST') return proposal.draftReason

  const companyFit = analysis.opportunity?.dimensions.find(
    (dimension) => dimension.type === 'COMPANY_FIT',
  )?.score
  if (companyFit == null) return proposal.draftReason

  const hasAnalyzedAttachment = detail.attachments.some(
    (attachment) => attachment.parseStatus === 'COMPLETED',
  )
  const blockers: string[] = []
  if (companyFit < 61) {
    blockers.push(
      `회사 적합도는 ${companyFit}점으로 사업 제안 준비안 생성 기준인 61점에 미달했습니다.`,
    )
  }
  if (analysis.eligibility === 'INELIGIBLE') {
    blockers.push(
      '신청 자격 또는 접수기한 조건상 현재 회사가 신청할 수 없는 공고로 판정했습니다.',
    )
  }
  if (!hasAnalyzedAttachment) {
    blockers.push(
      '내용 분석이 완료된 첨부 양식이 없어 제출 요구사항의 원문 근거를 확인할 수 없습니다.',
    )
  }
  if (blockers.length === 0) return proposal.draftReason
  if (analysis.eligibility === 'REVIEW_REQUIRED') {
    blockers.push('신청 자격은 회사의 공식 증빙으로 추가 확인해야 합니다.')
  }
  blockers.push('따라서 현재 확인된 조건으로는 사업 제안 준비안을 생성할 수 없습니다.')
  return blockers.join(' ')
}
