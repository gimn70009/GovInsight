import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ArrowLeft,
  CalendarDays,
  CircleHelp,
  ChevronDown,
  Download,
  ExternalLink,
  File,
  FileSearch2,
  Paperclip,
  RefreshCw,
  RotateCcw,
  Search,
  Sparkles,
} from 'lucide-react'
import { api } from '../api/client'
import type { ChangeType, DocumentAnalysis, DocumentDetail, DocumentDetection, MonitoringRun, OpportunityDimensionType, OpportunityPriority } from '../api/types'
import { Badge, EmptyState, InlineError, Loading, Pagination } from '../components/ui'

const opportunityLabels: Record<OpportunityDimensionType, string> = {
  COMPANY_FIT: '회사 적합도',
  BUSINESS_VALUE: '사업 매력도',
  FEASIBILITY: '실행 가능성',
  URGENCY: '긴급도',
  EVIDENCE_CONFIDENCE: '정보 신뢰도',
}
const opportunityOrder = Object.keys(opportunityLabels) as OpportunityDimensionType[]
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
  INELIGIBLE: ['지원 어려움', 'danger'],
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
      )
      setDocuments(data.content)
      setPages(data.totalPages)
      setTotal(data.totalElements)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '감지 문서를 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }, [page, appliedFrom, appliedTo, selectedRunId])

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

  useEffect(() => {
    setLoading(true)
    api.getDocument(detectionId)
      .then(setDetail)
      .catch((cause) => setError(cause instanceof Error ? cause.message : '상세 내용을 불러오지 못했습니다.'))
      .finally(() => setLoading(false))
  }, [detectionId])

  return (
    <div className="drawer-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <aside className="drawer">
        <div className="drawer__top">
          <button className="button button--subtle" onClick={onClose}><ArrowLeft size={17} />목록으로</button>
          {detail && <a className="button button--secondary" href={detail.originalUrl} target="_blank" rel="noreferrer">원문 보기<ExternalLink size={16} /></a>}
        </div>
        {loading ? <Loading label="문서 내용을 정리하고 있어요" /> : error ? <InlineError message={error} /> : detail && <DocumentContent detail={detail} />}
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
            <div><dt>회사 적합도</dt><dd>40%</dd></div>
            <div><dt>사업 매력도</dt><dd>20%</dd></div>
            <div><dt>실행 가능성</dt><dd>20%</dd></div>
            <div><dt>긴급도</dt><dd>10%</dd></div>
            <div><dt>정보 신뢰도</dt><dd>10%</dd></div>
          </dl>
          <p>회사 적합도가 40점 이하이면 종합 점수는 최대 49점이에요.</p>
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

function DocumentContent({ detail }: { detail: DocumentDetail }) {
  const analysis = detail.analysis
  const eligible = analysis ? eligibility[analysis.eligibility] : null
  const changeImpact = analysis ? getChangeImpact(detail, analysis.favorableOrNot) : null
  const proposalSections = analysis?.proposal.sections ?? []

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

      {analysis ? (
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
      ) : (
        <EmptyState icon={<Sparkles />} title="AI 분석을 준비하고 있어요" description="분석이 완료되면 핵심 내용과 대응 방향을 여기에서 확인할 수 있어요." />
      )}

      <section className="detail-section attachments">
        <div className="section-title-row"><h3>첨부파일</h3><Badge>{detail.attachments.length}개</Badge></div>
        {detail.attachments.length === 0 ? (
          <p className="muted">첨부된 파일이 없습니다.</p>
        ) : (
          detail.attachments.map((file) => (
            <a key={file.downloadUrl} href={file.downloadUrl} target="_blank" rel="noreferrer" className="attachment-row">
              <span className="file-icon"><File size={18} /></span>
              <span>
                <strong>{file.fileName}</strong>
                <small>{file.fileExtension.toUpperCase()} · {formatBytes(file.fileSize)} · {file.parseStatus === 'COMPLETED' ? '내용 분석 완료' : file.parseStatus === 'UNSUPPORTED' ? '내용 분석 미지원' : '내용 분석 실패'}</small>
              </span>
              <Download size={18} />
            </a>
          ))
        )}
      </section>
    </div>
  )
}
