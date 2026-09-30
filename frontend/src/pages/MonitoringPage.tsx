import './MonitoringPage.css'
import { MonitoringSourcesPanel } from './MonitoringSourcesPanel'
import { sourceSettingsChanges, type SourceDraft, type SourceDrafts } from '../utils/monitoringSourceSettings'
import { RunWarningTooltip } from '../components/RunWarningTooltip'
import { useToast } from '../hooks/useToast'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Activity, Building2, CalendarDays, Clock3, Play, RefreshCw, Save } from 'lucide-react'
import { api } from '../api/client'
import type { MonitoringRun, MonitoringSchedule, MonitoringScheduleFrequency, MonitoringSource, RunStatus, Weekday } from '../api/types'
import { Badge, EmptyState, InlineError, Loading, Pagination, Toast } from '../components/ui'

const runLabel: Record<RunStatus, string> = { REQUESTED: '요청됨', ACCEPTED: '접수됨', COLLECTED: '수집 완료', COMPLETED: '완료', FAILED: '실패' }
const runTone: Record<RunStatus, string> = { REQUESTED: 'info', ACCEPTED: 'info', COLLECTED: 'warning', COMPLETED: 'success', FAILED: 'danger' }
const formatDate = (value: string) => new Intl.DateTimeFormat('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
const weekdays: Array<{ value: Weekday; label: string }> = [
  { value: 'MONDAY', label: '월' }, { value: 'TUESDAY', label: '화' }, { value: 'WEDNESDAY', label: '수' },
  { value: 'THURSDAY', label: '목' }, { value: 'FRIDAY', label: '금' }, { value: 'SATURDAY', label: '토' }, { value: 'SUNDAY', label: '일' },
]
const defaultSchedule: MonitoringSchedule = { enabled: false, frequency: 'DAILY', executionTime: '09:00', customDays: [] }

export default function MonitoringPage() {
  const [sources, setSources] = useState<MonitoringSource[]>([])
  const [runs, setRuns] = useState<MonitoringRun[]>([])
  const [runPage, setRunPage] = useState(0)
  const [runPages, setRunPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [running, setRunning] = useState(false)
  const [toast, setToast] = useToast()
  const [schedule, setSchedule] = useState<MonitoringSchedule>(defaultSchedule)
  const [savedSchedule, setSavedSchedule] = useState<MonitoringSchedule | null>(null)
  const [savingSchedule, setSavingSchedule] = useState(false)
  const [sourceDrafts, setSourceDrafts] = useState<SourceDrafts>({})
  const [savingSources, setSavingSources] = useState(false)
  const [sourceError, setSourceError] = useState('')
  const sourceSaveRef = useRef(false)
  const sourceRevision = useRef(0)
  const scheduleSaveRef = useRef(false)
  const scheduleRevision = useRef(0)

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    const revision = scheduleRevision.current
    const sourcesRevision = sourceRevision.current
    try {
      const [sourceData, runData, scheduleData] = await Promise.all([
        api.getSources(),
        api.getRuns(runPage, 8),
        api.getMonitoringSchedule().catch(() => null),
      ])
      if (sourcesRevision === sourceRevision.current && !sourceSaveRef.current) {
        setSources(sourceData)
      }
      setRuns(runData.content)
      setRunPages(runData.totalPages)
      if (scheduleData && revision === scheduleRevision.current && !scheduleSaveRef.current) {
        setSchedule({ ...scheduleData, executionTime: scheduleData.executionTime.slice(0, 5) })
        setSavedSchedule(scheduleData)
      } else if (!scheduleData) {
        setError('자동 모니터링 설정을 불러오지 못했습니다. Backend를 최신 코드로 다시 시작해 주세요.')
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '모니터링 정보를 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }, [runPage])

  useEffect(() => { void load() }, [load])

  useEffect(() => {
    const refreshRuns = async () => {
      if (document.visibilityState !== 'visible') return
      try {
        const runData = await api.getRuns(runPage, 8)
        setRuns(runData.content)
        setRunPages(runData.totalPages)
      } catch {
        // 최초 조회 오류는 기존 안내 영역에서 처리하고, 백그라운드 갱신 실패는 화면을 방해하지 않는다.
      }
    }
    const intervalId = window.setInterval(() => { void refreshRuns() }, 5_000)
    return () => window.clearInterval(intervalId)
  }, [runPage])

  const activeCount = sources.filter((source) => source.enabled).length

  const sourceChanges = sourceSettingsChanges(sources, sourceDrafts)

  const changeSource = (sourceId: number, draft: SourceDraft) => {
    if (loading || sourceSaveRef.current) return
    sourceRevision.current++
    setSourceDrafts((current) => ({ ...current, [sourceId]: draft }))
    setSourceError('')
  }

  const saveSources = async () => {
    if (loading || sourceSaveRef.current || sourceChanges.dirtyCount === 0 || sourceChanges.invalidSourceIds.length > 0) return
    sourceSaveRef.current = true
    sourceRevision.current++
    setSavingSources(true)
    setSourceError('')
    try {
      const updated = await api.updateSourceSettings(sourceChanges.updates)
      setSources((items) => items.map((item) => updated.find((value) => value.sourceId === item.sourceId) ?? item))
      setSourceDrafts({})
      setToast('모니터링 소스 설정을 저장했어요.')
    } catch (cause) {
      setSourceError(cause instanceof Error ? cause.message : '설정을 저장하지 못했어요. 다시 시도해 주세요.')
    } finally {
      sourceRevision.current++
      sourceSaveRef.current = false
      setSavingSources(false)
    }
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

  const changeFrequency = (frequency: MonitoringScheduleFrequency) => {
    setSchedule((current) => ({
      ...current,
      frequency,
      customDays: frequency === 'CUSTOM' && current.frequency !== 'CUSTOM'
        ? ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']
        : current.customDays,
    }))
  }

  const toggleDay = (day: Weekday) => {
    setSchedule((current) => ({
      ...current,
      customDays: current.customDays.includes(day)
        ? current.customDays.filter((item) => item !== day)
        : [...current.customDays, day],
    }))
  }

  const saveSchedule = async (nextSchedule = schedule, enabledOnly = false) => {
    if (scheduleSaveRef.current || loading || !savedSchedule) return
    if (nextSchedule.frequency === 'CUSTOM' && nextSchedule.customDays.length === 0) {
      setError('자동 실행할 요일을 하나 이상 선택해 주세요.')
      return
    }
    scheduleSaveRef.current = true
    scheduleRevision.current++
    setSavingSchedule(true); setError('')
    try {
      const updated = await api.updateMonitoringSchedule(nextSchedule)
      setSavedSchedule(updated)
      setSchedule((current) => enabledOnly
        ? { ...current, enabled: updated.enabled }
        : { ...updated, executionTime: updated.executionTime.slice(0, 5) })
      setToast(enabledOnly
        ? updated.enabled ? '자동 모니터링을 켰어요.' : '자동 모니터링을 껐어요.'
        : '자동 모니터링 일정을 저장했어요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '자동 모니터링 일정을 저장하지 못했습니다.') }
    finally { scheduleRevision.current++; scheduleSaveRef.current = false; setSavingSchedule(false) }
  }

  return (
    <div className="page">
      <header className="page-header"><div><span className="eyebrow">MONITORING</span><h1>모니터링</h1><p>살펴볼 기관을 관리하고, 필요할 때 바로 모니터링을 시작하세요.</p></div><button className="button button--primary button--run" onClick={runNow} disabled={loading || running || savingSources || sourceChanges.dirtyCount > 0 || activeCount === 0}>{running ? <RefreshCw size={18} className="spin" /> : <Play size={18} fill="currentColor" />}{running ? '확인 중이에요' : '지금 모니터링 실행'}</button></header>
      {error && <InlineError message={error} />}
      <section className="metric-grid">
        <article className="metric-card"><span className="metric-card__icon metric-card__icon--blue"><Building2 size={20} /></span><div><small>등록된 소스</small><strong>{sources.length}<em>개</em></strong><span>{activeCount}개가 확인 중이에요</span></div></article>
        <article className="metric-card"><span className="metric-card__icon metric-card__icon--green"><Activity size={20} /></span><div><small>최근 실행 상태</small><strong className="metric-card__status">{runs[0] ? runLabel[runs[0].status] : '기록 없음'}</strong><span>{runs[0] ? formatDate(runs[0].requestedAt) : '첫 실행을 기다리고 있어요'}</span></div></article>
        <article className="metric-card"><span className="metric-card__icon metric-card__icon--violet"><Clock3 size={20} /></span><div><small>최근 감지 문서</small><strong>{runs[0]?.detectedDocumentCount ?? 0}<em>건</em></strong><span>마지막 실행 기준</span></div></article>
      </section>

      <section className="panel schedule-panel">
        <div className="panel-header"><div><h2>자동 모니터링</h2><p>한국 시간 기준으로 선택한 요일과 시각에 활성 소스를 자동으로 확인하고 Telegram 보고서를 보내요.</p></div><button className={`schedule-switch ${schedule.enabled ? 'schedule-switch--on' : ''}`} role="switch" aria-label="자동 모니터링 사용" aria-checked={schedule.enabled} aria-busy={savingSchedule} disabled={loading || savingSchedule || !savedSchedule} onClick={() => { if (savedSchedule) void saveSchedule({ ...savedSchedule, enabled: !savedSchedule.enabled }, true) }}><span>{schedule.enabled ? '사용 중' : '사용 안 함'}</span><i /></button></div>
        <div className="schedule-panel__body">
          <div className="schedule-field"><span><CalendarDays size={16} />실행 주기</span><div className="schedule-frequency">{([['DAILY', '매일'], ['WEEKDAYS', '평일'], ['CUSTOM', '요일 선택']] as Array<[MonitoringScheduleFrequency, string]>).map(([value, label]) => <button key={value} className={schedule.frequency === value ? 'active' : ''} disabled={loading || savingSchedule || !savedSchedule} onClick={() => changeFrequency(value)}>{label}</button>)}</div></div>
          {schedule.frequency === 'CUSTOM' && <div className="schedule-field"><span>실행 요일</span><div className="weekday-picker">{weekdays.map((day) => <button key={day.value} className={schedule.customDays.includes(day.value) ? 'active' : ''} disabled={loading || savingSchedule || !savedSchedule} onClick={() => toggleDay(day.value)}>{day.label}</button>)}</div></div>}
          <label className="schedule-field schedule-time"><span><Clock3 size={16} />실행 시각</span><input type="time" disabled={loading || savingSchedule || !savedSchedule} value={schedule.executionTime} onChange={(event) => setSchedule((current) => ({ ...current, executionTime: event.target.value }))} /></label>
          <button className="button button--primary schedule-save" onClick={() => void saveSchedule()} disabled={loading || savingSchedule || !savedSchedule}><Save size={16} />{savingSchedule ? '저장 중...' : '일정 저장'}</button>
        </div>
      </section>

      <MonitoringSourcesPanel sources={sources} drafts={sourceDrafts} loading={loading} saving={savingSources} error={sourceError} onChange={changeSource} onSave={saveSources} />

      <section className="panel">
        <div className="panel-header"><div><h2>최근 실행 이력</h2><p>모니터링이 어떻게 진행됐는지 빠르게 확인하세요.</p></div><button className="button button--subtle" onClick={() => void load()}><RefreshCw size={16} />새로고침</button></div>
        {loading ? <Loading /> : runs.length === 0 ? <EmptyState icon={<Clock3 />} title="아직 실행 이력이 없어요" description="모니터링을 실행하면 처리 과정과 결과가 여기에 쌓여요." /> : <div className="table-wrap"><table className="monitoring-run-table"><thead><tr><th scope="col">실행 시각</th><th scope="col">상태</th><th scope="col">소스</th><th scope="col">감지 문서</th><th scope="col">경고</th></tr></thead><tbody>{runs.map((run) => <tr key={run.runId}><td><strong>{formatDate(run.requestedAt)}</strong><small className="block muted">{run.triggerType === 'MANUAL' ? '수동 실행' : '자동 실행'}</small></td><td><Badge tone={runTone[run.status]}>{runLabel[run.status]}</Badge></td><td>{run.totalSourceCount}개</td><td><b>{run.detectedDocumentCount}</b>건</td><td><RunWarningTooltip runId={run.runId} warningCount={run.warningCount} status={run.status} /></td></tr>)}</tbody></table></div>}
        <Pagination page={runPage} totalPages={runPages} onChange={setRunPage} />
      </section>
      {toast && <Toast message={toast} onClose={() => setToast('')} />}
    </div>
  )
}
