import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Activity, Building2, CirclePlus, Clock3, Pencil, Play, RefreshCw, Search, X } from 'lucide-react'
import { api } from '../api/client'
import type { MonitoringRun, MonitoringSource, MonitoringSourcePayload, RunStatus } from '../api/types'
import { Badge, EmptyState, InlineError, Loading, Pagination, Toast } from '../components/ui'

const emptyForm: MonitoringSourcePayload = { organizationName: '', boardName: '', description: '', listUrl: '', urlIncludePattern: '', detailFetchCount: 3, enabled: true }
const runLabel: Record<RunStatus, string> = { REQUESTED: '요청됨', ACCEPTED: '접수됨', COLLECTED: '수집 완료', COMPLETED: '완료', FAILED: '실패' }
const runTone: Record<RunStatus, string> = { REQUESTED: 'info', ACCEPTED: 'info', COLLECTED: 'warning', COMPLETED: 'success', FAILED: 'danger' }
const formatDate = (value: string) => new Intl.DateTimeFormat('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))

export default function MonitoringPage() {
  const [sources, setSources] = useState<MonitoringSource[]>([])
  const [runs, setRuns] = useState<MonitoringRun[]>([])
  const [runPage, setRunPage] = useState(0)
  const [runPages, setRunPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [modal, setModal] = useState<{ open: boolean; source?: MonitoringSource }>({ open: false })
  const [running, setRunning] = useState(false)
  const [toast, setToast] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [sourceData, runData] = await Promise.all([api.getSources(), api.getRuns(runPage, 8)])
      setSources(sourceData)
      setRuns(runData.content)
      setRunPages(runData.totalPages)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '모니터링 정보를 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }, [runPage])

  useEffect(() => { void load() }, [load])

  const filtered = useMemo(() => sources.filter((source) => `${source.organizationName} ${source.boardName}`.toLowerCase().includes(query.toLowerCase())), [sources, query])
  const activeCount = sources.filter((source) => source.enabled).length

  const toggle = async (source: MonitoringSource) => {
    try {
      const updated = await api.changeSourceEnabled(source.sourceId, !source.enabled)
      setSources((items) => items.map((item) => item.sourceId === updated.sourceId ? updated : item))
      setToast(updated.enabled ? '모니터링을 다시 시작했어요.' : '모니터링을 잠시 멈췄어요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '상태를 변경하지 못했습니다.') }
  }

  const runNow = async () => {
    setRunning(true)
    try {
      await api.createRun()
      setToast('모니터링을 시작했어요. 결과가 준비되면 이력에 표시됩니다.')
      setRunPage(0)
      await load()
    } catch (cause) { setError(cause instanceof Error ? cause.message : '모니터링을 시작하지 못했습니다.') }
    finally { setRunning(false) }
  }

  return (
    <div className="page">
      <header className="page-header"><div><span className="eyebrow">MONITORING</span><h1>모니터링</h1><p>살펴볼 기관을 관리하고, 필요할 때 바로 모니터링을 시작하세요.</p></div><button className="button button--primary button--run" onClick={runNow} disabled={running || activeCount === 0}>{running ? <RefreshCw size={18} className="spin" /> : <Play size={18} fill="currentColor" />}{running ? '확인 중이에요' : '지금 모니터링 실행'}</button></header>
      {error && <InlineError message={error} />}
      <section className="metric-grid">
        <article className="metric-card"><span className="metric-card__icon metric-card__icon--blue"><Building2 size={20} /></span><div><small>등록된 소스</small><strong>{sources.length}<em>개</em></strong><span>{activeCount}개가 확인 중이에요</span></div></article>
        <article className="metric-card"><span className="metric-card__icon metric-card__icon--green"><Activity size={20} /></span><div><small>최근 실행 상태</small><strong className="metric-card__status">{runs[0] ? runLabel[runs[0].status] : '기록 없음'}</strong><span>{runs[0] ? formatDate(runs[0].requestedAt) : '첫 실행을 기다리고 있어요'}</span></div></article>
        <article className="metric-card"><span className="metric-card__icon metric-card__icon--violet"><Clock3 size={20} /></span><div><small>최근 감지 문서</small><strong>{runs[0]?.detectedDocumentCount ?? 0}<em>건</em></strong><span>마지막 실행 기준</span></div></article>
      </section>

      <section className="panel">
        <div className="panel-header"><div><h2>모니터링 소스</h2><p>새 글과 변경 사항을 확인할 공공기관 게시판이에요.</p></div><div className="panel-actions"><label className="search-box"><Search size={17} /><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="기관 또는 게시판 검색" /></label><button className="button button--secondary" onClick={() => setModal({ open: true })}><CirclePlus size={17} />소스 등록</button></div></div>
        {loading ? <Loading /> : filtered.length === 0 ? <EmptyState icon={<Building2 />} title="등록된 소스가 없어요" description="첫 모니터링 소스를 등록해 변화를 확인해 보세요." /> : (
          <div className="table-wrap"><table><thead><tr><th>기관 / 게시판</th><th>수집 범위</th><th>상태</th><th>최근 수정</th><th aria-label="관리"></th></tr></thead><tbody>{filtered.map((source) => <tr key={source.sourceId}><td><div className="primary-cell"><span className="source-logo">{source.organizationName.slice(0, 1)}</span><span><strong>{source.organizationName}</strong><small>{source.boardName}</small></span></div></td><td><span className="count-value">최근 {source.detailFetchCount}건</span><small className="url-preview">{source.listUrl}</small></td><td><button className={`toggle ${source.enabled ? 'toggle--on' : ''}`} onClick={() => void toggle(source)} aria-label={`${source.organizationName} 활성 상태 변경`}><span /></button><small className="toggle-label">{source.enabled ? '확인 중' : '일시 중지'}</small></td><td className="muted">{formatDate(source.updatedAt)}</td><td><button className="icon-button" onClick={() => setModal({ open: true, source })} title="소스 수정"><Pencil size={17} /></button></td></tr>)}</tbody></table></div>
        )}
      </section>

      <section className="panel">
        <div className="panel-header"><div><h2>최근 실행 이력</h2><p>모니터링이 어떻게 진행됐는지 빠르게 확인하세요.</p></div><button className="button button--subtle" onClick={() => void load()}><RefreshCw size={16} />새로고침</button></div>
        {loading ? <Loading /> : runs.length === 0 ? <EmptyState icon={<Clock3 />} title="아직 실행 이력이 없어요" description="모니터링을 실행하면 처리 과정과 결과가 여기에 쌓여요." /> : <div className="table-wrap"><table><thead><tr><th>실행 시각</th><th>상태</th><th>소스</th><th>감지 문서</th><th>경고</th><th>보고서</th></tr></thead><tbody>{runs.map((run) => <tr key={run.runId}><td><strong>{formatDate(run.requestedAt)}</strong><small className="block muted">{run.triggerType === 'MANUAL' ? '수동 실행' : '자동 실행'}</small></td><td><Badge tone={runTone[run.status]}>{runLabel[run.status]}</Badge></td><td>{run.totalSourceCount}개</td><td><b>{run.detectedDocumentCount}</b>건</td><td>{run.warningCount > 0 ? <Badge tone="warning">{run.warningCount}건</Badge> : <span className="muted">없음</span>}</td><td>{run.reportTitle ? <span className="report-title">{run.reportTitle}</span> : <span className="muted">준비 중</span>}</td></tr>)}</tbody></table></div>}
        <Pagination page={runPage} totalPages={runPages} onChange={setRunPage} />
      </section>
      {modal.open && <SourceModal source={modal.source} onClose={() => setModal({ open: false })} onSaved={(source) => { setSources((items) => modal.source ? items.map((item) => item.sourceId === source.sourceId ? source : item) : [source, ...items]); setModal({ open: false }); setToast(modal.source ? '소스 정보를 수정했어요.' : '새 모니터링 소스를 등록했어요.') }} />}
      {toast && <Toast message={toast} onClose={() => setToast('')} />}
    </div>
  )
}

function SourceModal({ source, onClose, onSaved }: { source?: MonitoringSource; onClose: () => void; onSaved: (source: MonitoringSource) => void }) {
  const [form, setForm] = useState<MonitoringSourcePayload>(source ? { organizationName: source.organizationName, boardName: source.boardName, description: source.description ?? '', listUrl: source.listUrl, urlIncludePattern: source.urlIncludePattern ?? '', detailFetchCount: source.detailFetchCount, enabled: source.enabled } : emptyForm)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const set = <K extends keyof MonitoringSourcePayload>(key: K, value: MonitoringSourcePayload[K]) => setForm((current) => ({ ...current, [key]: value }))
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError('')
    const payload = { ...form, description: form.description || null, urlIncludePattern: form.urlIncludePattern || null }
    try { onSaved(source ? await api.updateSource(source.sourceId, payload) : await api.createSource(payload)) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '소스를 저장하지 못했습니다.') }
    finally { setSaving(false) }
  }
  return <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}><form className="modal" onSubmit={submit}><div className="modal__header"><div><span className="eyebrow">MONITORING SOURCE</span><h2>{source ? '소스 정보 수정' : '새 모니터링 소스'}</h2><p>확인할 기관과 게시판 정보를 알려주세요.</p></div><button type="button" className="icon-button" onClick={onClose}><X /></button></div><div className="modal__body"><div className="form-grid"><label className="field"><span>기관명 *</span><input value={form.organizationName} onChange={(e) => set('organizationName', e.target.value)} placeholder="예: 과학기술정보통신부" required /></label><label className="field"><span>게시판명 *</span><input value={form.boardName} onChange={(e) => set('boardName', e.target.value)} placeholder="예: 사업공고" required /></label></div><label className="field"><span>목록 페이지 URL *</span><input type="url" value={form.listUrl} onChange={(e) => set('listUrl', e.target.value)} placeholder="https://..." required /></label><label className="field"><span>상세 URL 포함 패턴 <em>선택</em></span><input value={form.urlIncludePattern ?? ''} onChange={(e) => set('urlIncludePattern', e.target.value)} placeholder="예: /bbs/view.do" /><small>목록에서 실제 게시글 링크만 골라낼 때 사용해요.</small></label><div className="form-grid"><label className="field"><span>한 번에 확인할 게시글 수</span><input type="number" min="1" value={form.detailFetchCount} onChange={(e) => set('detailFetchCount', Number(e.target.value))} required /></label><label className="field"><span>활성 상태</span><button type="button" className={`segmented-choice ${form.enabled ? 'segmented-choice--active' : ''}`} onClick={() => set('enabled', !form.enabled)}><span>{form.enabled ? '바로 모니터링 시작' : '일시 중지로 등록'}</span><i /></button></label></div><label className="field"><span>설명 <em>선택</em></span><textarea value={form.description ?? ''} onChange={(e) => set('description', e.target.value)} placeholder="이 게시판에서 어떤 정보를 확인하는지 적어두세요." rows={3} /></label>{error && <InlineError message={error} />}</div><div className="modal__footer"><button type="button" className="button button--subtle" onClick={onClose}>취소</button><button className="button button--primary" disabled={saving}>{saving ? '저장 중...' : source ? '수정 완료' : '소스 등록'}</button></div></form></div>
}
