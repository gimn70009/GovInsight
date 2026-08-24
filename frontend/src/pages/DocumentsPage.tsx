import { useCallback, useEffect, useMemo, useState } from 'react'
import { ArrowLeft, CalendarDays, Download, ExternalLink, File, FileSearch2, Paperclip, RefreshCw, Search, Sparkles } from 'lucide-react'
import { api } from '../api/client'
import type { ChangeType, DocumentDetail, DocumentDetection, Importance } from '../api/types'
import { Badge, EmptyState, InlineError, Loading, Pagination } from '../components/ui'

const importanceLabel: Record<Importance, string> = { HIGH: '중요', NORMAL: '보통', LOW: '낮음' }
const importanceTone: Record<Importance, string> = { HIGH: 'danger', NORMAL: 'warning', LOW: 'neutral' }
const changeLabel: Record<ChangeType, string> = { NEW_DOCUMENT: '신규', UPDATED_DOCUMENT: '수정', UNCHANGED_DOCUMENT: '변경 없음' }
const eligibility = { ELIGIBLE: ['지원 가능', 'success'], INELIGIBLE: ['지원 어려움', 'danger'], REVIEW_REQUIRED: ['추가 검토 필요', 'warning'] } as const
const favorability = { FAVORABLE: ['유리한 변경', 'success'], UNFAVORABLE: ['불리한 변경', 'danger'], NEUTRAL: ['영향 없음', 'neutral'], NOT_APPLICABLE: ['신규 문서', 'info'], REVIEW_REQUIRED: ['추가 검토 필요', 'warning'] } as const
const formatDate = (value: string | null) => value ? new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value)) : '게시일 미확인'
const formatDateTime = (value: string) => new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
const formatBytes = (value: number | null) => value == null ? '크기 미확인' : value < 1024 * 1024 ? `${Math.ceil(value / 1024)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`

export default function DocumentsPage() {
  const [documents, setDocuments] = useState<DocumentDetection[]>([])
  const [page, setPage] = useState(0)
  const [pages, setPages] = useState(0)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [importance, setImportance] = useState<'ALL' | Importance>('ALL')
  const [selectedId, setSelectedId] = useState<number | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setError('')
    try { const data = await api.getDocuments(page, 20); setDocuments(data.content); setPages(data.totalPages); setTotal(data.totalElements) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '감지 문서를 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }, [page])
  useEffect(() => { void load() }, [load])

  const filtered = useMemo(() => documents.filter((item) => {
    const matchesQuery = `${item.organizationName} ${item.boardName} ${item.title}`.toLowerCase().includes(query.toLowerCase())
    return matchesQuery && (importance === 'ALL' || item.importance === importance)
  }), [documents, query, importance])

  return <div className="page"><header className="page-header"><div><span className="eyebrow">DOCUMENTS</span><h1>감지된 게시글</h1><p>새로 올라오거나 달라진 공고를 확인하고, 우리 회사에 필요한 내용을 살펴보세요.</p></div><div className="header-stat"><small>확인된 문서</small><strong>{total.toLocaleString()}<em>건</em></strong></div></header>{error && <InlineError message={error} />}<section className="panel documents-panel"><div className="panel-header"><div className="filter-tabs">{(['ALL', 'HIGH', 'NORMAL', 'LOW'] as const).map((value) => <button key={value} className={importance === value ? 'active' : ''} onClick={() => setImportance(value)}>{value === 'ALL' ? '전체' : importanceLabel[value]}</button>)}</div><div className="panel-actions"><label className="search-box"><Search size={17} /><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="기관, 게시판, 제목 검색" /></label><button className="icon-button" onClick={() => void load()} title="새로고침"><RefreshCw size={17} /></button></div></div>{loading ? <Loading /> : filtered.length === 0 ? <EmptyState icon={<FileSearch2 />} title="조건에 맞는 게시글이 없어요" description="검색어나 중요도 필터를 바꿔 다시 확인해 보세요." /> : <div className="document-list">{filtered.map((item) => <button className="document-row" key={item.detectionId} onClick={() => setSelectedId(item.detectionId)}><div className="document-row__org"><span className="source-logo">{item.organizationName.slice(0, 1)}</span><span><strong>{item.organizationName}</strong><small>{item.boardName}</small></span></div><div className="document-row__title"><strong>{item.title}</strong><span><Badge tone={item.changeType === 'UPDATED_DOCUMENT' ? 'warning' : item.changeType === 'NEW_DOCUMENT' ? 'info' : 'neutral'}>{changeLabel[item.changeType]}</Badge><small><Paperclip size={13} />{item.attachmentCount}개</small></span></div><div className="document-row__importance">{item.importance ? <Badge tone={importanceTone[item.importance]}>{importanceLabel[item.importance]}</Badge> : <Badge>분석 중</Badge>}</div><time>{formatDateTime(item.lastCheckedAt)}</time></button>)}</div>}<Pagination page={page} totalPages={pages} onChange={setPage} /></section>{selectedId && <DocumentDrawer detectionId={selectedId} onClose={() => setSelectedId(null)} />}</div>
}

function DocumentDrawer({ detectionId, onClose }: { detectionId: number; onClose: () => void }) {
  const [detail, setDetail] = useState<DocumentDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  useEffect(() => { setLoading(true); api.getDocument(detectionId).then(setDetail).catch((cause) => setError(cause instanceof Error ? cause.message : '상세 내용을 불러오지 못했습니다.')).finally(() => setLoading(false)) }, [detectionId])
  return <div className="drawer-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}><aside className="drawer"><div className="drawer__top"><button className="button button--subtle" onClick={onClose}><ArrowLeft size={17} />목록으로</button>{detail && <a className="button button--secondary" href={detail.originalUrl} target="_blank" rel="noreferrer">원문 보기<ExternalLink size={16} /></a>}</div>{loading ? <Loading label="문서 내용을 정리하고 있어요" /> : error ? <InlineError message={error} /> : detail && <DocumentContent detail={detail} />}</aside></div>
}

function DocumentContent({ detail }: { detail: DocumentDetail }) {
  const analysis = detail.analysis
  const eligible = analysis ? eligibility[analysis.eligibility] : null
  const favorable = analysis ? favorability[analysis.favorableOrNot] : null
  return <div className="document-detail"><header className="document-detail__header"><div className="document-kicker"><span>{detail.organizationName}</span><i />{detail.boardName}</div><h2>{detail.title}</h2><div className="document-meta"><span><CalendarDays size={15} />{formatDate(detail.publishedAt)}</span><Badge tone={detail.changeType === 'UPDATED_DOCUMENT' ? 'warning' : detail.changeType === 'NEW_DOCUMENT' ? 'info' : 'neutral'}>{changeLabel[detail.changeType]}</Badge><span>마지막 확인 {formatDateTime(detail.lastCheckedAt)}</span></div></header>{analysis ? <><section className="analysis-hero"><div className="analysis-hero__title"><span><Sparkles size={18} /></span><div><small>AI ANALYSIS</small><h3>이 문서, 이렇게 이해하면 돼요</h3></div><Badge tone={importanceTone[analysis.importance]}>{importanceLabel[analysis.importance]}</Badge></div><p className="summary-text">{analysis.summary}</p></section><section className="detail-section"><h3>핵심 내용</h3><ul className="key-points">{analysis.keyPoints.map((point, index) => <li key={`${point}-${index}`}><span>{index + 1}</span><p>{point}</p></li>)}</ul></section><div className="insight-grid"><section className="insight-card"><small>지원 가능성</small><div><Badge tone={eligible![1]}>{eligible![0]}</Badge></div><p>공개된 회사 정보와 공고 조건을 기준으로 판단했어요.</p></section><section className="insight-card"><small>변경 영향</small><div><Badge tone={favorable![1]}>{favorable![0]}</Badge></div><p>이전 공고와 비교했을 때 회사에 미치는 영향이에요.</p></section></div><section className="detail-section reason-box"><h3>왜 중요하게 봤나요?</h3><p>{analysis.reason}</p></section><section className="detail-section proposal-box"><span className="proposal-box__icon"><Sparkles size={18} /></span><div><h3>우리 회사는 이렇게 준비해 보세요</h3><p>{analysis.proposalDirection}</p></div></section></> : <EmptyState icon={<Sparkles />} title="AI 분석을 준비하고 있어요" description="분석이 완료되면 핵심 내용과 대응 방향을 여기에서 확인할 수 있어요." />}<section className="detail-section attachments"><div className="section-title-row"><h3>첨부파일</h3><Badge>{detail.attachments.length}개</Badge></div>{detail.attachments.length === 0 ? <p className="muted">첨부된 파일이 없습니다.</p> : detail.attachments.map((file) => <a key={file.downloadUrl} href={file.downloadUrl} target="_blank" rel="noreferrer" className="attachment-row"><span className="file-icon"><File size={18} /></span><span><strong>{file.fileName}</strong><small>{file.fileExtension.toUpperCase()} · {formatBytes(file.fileSize)} · {file.parseStatus === 'COMPLETED' ? '내용 분석 완료' : file.parseStatus === 'UNSUPPORTED' ? '내용 분석 미지원' : '내용 분석 실패'}</small></span><Download size={18} /></a>)}</section></div>
}
