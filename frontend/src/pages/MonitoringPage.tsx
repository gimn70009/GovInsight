import './MonitoringPage.css'
import { MonitoringSourcesPanel } from './MonitoringSourcesPanel'
import { sourceSettingsChanges, type SourceDraft, type SourceDrafts } from '../utils/monitoringSourceSettings'
import { RunWarningTooltip } from '../components/RunWarningTooltip'
import { useToast } from '../hooks/useToast'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Activity, Building2, CalendarDays, Clock3, Play, RefreshCw, Save } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { MonitoringRunActivity, MonitoringRun, MonitoringSchedule, MonitoringSchedulePayload, MonitoringScheduleFrequency, MonitoringSource, RunStatus, Weekday } from '../api/types'
import { Badge, EmptyState, InlineError, Loading, Pagination, Toast } from '../components/ui'

const runLabel: Record<RunStatus, string> = { REQUESTED: '요청됨', ACCEPTED: '접수됨', RUNNING: '수집 중', COLLECTED: '수집 완료', COMPLETED: '완료', FAILED: '실패' }
const runTone: Record<RunStatus, string> = { REQUESTED: 'info', ACCEPTED: 'info', RUNNING: 'info', COLLECTED: 'warning', COMPLETED: 'success', FAILED: 'danger' }
const formatDate = (value: string) => new Intl.DateTimeFormat('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
const weekdays: Array<{ value: Weekday; label: string }> = [
  { value: 'MONDAY', label: '월' }, { value: 'TUESDAY', label: '화' }, { value: 'WEDNESDAY', label: '수' },
  { value: 'THURSDAY', label: '목' }, { value: 'FRIDAY', label: '금' }, { value: 'SATURDAY', label: '토' }, { value: 'SUNDAY', label: '일' },
]
const defaultSchedule: MonitoringSchedulePayload = { enabled: false, frequency: 'DAILY', executionTime: '09:00', customDays: [] }
const scheduleFields = (value: MonitoringSchedule): MonitoringSchedulePayload => ({
  enabled: value.enabled, frequency: value.frequency, executionTime: value.executionTime.slice(0, 5), customDays: value.customDays,
})

function pendingScheduleMessage(
  checking: boolean,
  ready: boolean,
  failed: boolean,
  activity: MonitoringRunActivity | null,
  activeSources: number,
) {
  if (checking) return '예약 실행 상태를 확인하고 있습니다.'
  if (!ready) return failed
    ? '예약 실행 상태를 확인하지 못했습니다. 잠시 후 다시 확인합니다.'
    : '예약 실행 상태를 확인하고 있습니다.'
  if (activeSources === 0) return activity?.running
    ? '진행 중인 모니터링이 끝나고 활성 소스가 설정되면 시작합니다.'
    : '활성 소스가 없어 기다리고 있습니다.'
  return activity?.running
    ? '앞선 모니터링이 끝나면 자동으로 시작합니다.'
    : '예약 모니터링의 자동 시작을 기다리고 있습니다.'
}

export default function MonitoringPage() {
  const [sources, setSources] = useState<MonitoringSource[]>([])
  const [runs, setRuns] = useState<MonitoringRun[]>([])
  const [runPage, setRunPage] = useState(0)
  const [runPages, setRunPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [running, setRunning] = useState(false)
  const [activity, setActivity] = useState<MonitoringRunActivity | null>(null)
  const [activityError, setActivityError] = useState('')
  const runRequestRef = useRef(false)
  const activitySequence = useRef(0)
  const statusRequest = useRef<AbortController | null>(null)
  const [statusChecking, setStatusChecking] = useState(false)
  const [sourcesReady, setSourcesReady] = useState(false)
  const [sourcesReadError, setSourcesReadError] = useState('')
  const [toast, setToast] = useToast()
  const [schedule, setSchedule] = useState<MonitoringSchedulePayload>(defaultSchedule)
  const [savedSchedule, setSavedSchedule] = useState<MonitoringSchedule | null>(null)
  const [savingSchedule, setSavingSchedule] = useState(false)
  const [sourceDrafts, setSourceDrafts] = useState<SourceDrafts>({})
  const [savingSources, setSavingSources] = useState(false)
  const [sourceError, setSourceError] = useState('')
  const sourceSaveRef = useRef(false)
  const sourceRevision = useRef(0)
  const scheduleSaveRef = useRef(false)
  const scheduleRevision = useRef(0)
  const scheduleEdited = useRef(false)
  const [pendingScheduledAt, setPendingScheduledAt] = useState<string | null>(null)
  const [scheduleReady, setScheduleReady] = useState(false)
  const [scheduleError, setScheduleError] = useState('')
  const [cancellingPending, setCancellingPending] = useState(false)
  const [pendingError, setPendingError] = useState('')

  const receiveSchedule = useCallback((value: MonitoringSchedule) => {
    setSavedSchedule(value)
    setPendingScheduledAt(value.pendingScheduledAt ?? null)
    setScheduleReady(true)
    setScheduleError('')
    setSchedule((current) => scheduleEdited.current
      ? { ...current, enabled: value.enabled }
      : scheduleFields(value))
  }, [])

  const refreshActivity = useCallback(async (force = false) => {
    if (runRequestRef.current || (statusRequest.current && !force)) return
    statusRequest.current?.abort()
    const controller = new AbortController()
    statusRequest.current = controller
    const sequence = ++activitySequence.current
    const scheduleVersion = scheduleRevision.current
    const sourceVersion = sourceRevision.current
    const readSchedule = !scheduleSaveRef.current
    const readSources = !sourceSaveRef.current
    // Keep the last confirmed message during fast polls, but never leave it
    // presented as current while a status request is stuck.
    const slowTimer = window.setTimeout(() => {
      if (sequence === activitySequence.current) setStatusChecking(true)
    }, 500)
    const timeout = window.setTimeout(() => controller.abort(), 10_000)
    const [activityResult, scheduleResult, sourcesResult] = await Promise.allSettled([
      api.getRunActivity(controller.signal),
      readSchedule ? api.getMonitoringSchedule(controller.signal) : Promise.resolve(null),
      readSources ? api.getSources(controller.signal) : Promise.resolve(null),
    ])
    window.clearTimeout(slowTimer)
    window.clearTimeout(timeout)
    if (sequence !== activitySequence.current) return
    statusRequest.current = null
    setStatusChecking(false)

    if (activityResult.status === 'fulfilled') {
      setActivity(activityResult.value)
      setActivityError('')
    } else {
      setActivity(null)
      setActivityError('진행 중인 모니터링을 확인하지 못했습니다. 잠시 후 다시 확인합니다.')
    }

    if (readSchedule && scheduleVersion === scheduleRevision.current && !scheduleSaveRef.current) {
      if (scheduleResult.status === 'fulfilled' && scheduleResult.value) {
        receiveSchedule(scheduleResult.value)
      } else if (scheduleResult.status === 'rejected') {
        const cause = scheduleResult.reason
        if (cause instanceof ApiError && cause.status === 404) {
          setPendingScheduledAt(null)
          setSavedSchedule(null)
          setSchedule((current) => ({ ...current, enabled: false }))
          setScheduleReady(true)
          setScheduleError('자동 모니터링 기능이 비활성화되어 있습니다.')
        } else {
          setScheduleReady(false)
          setScheduleError('예약 대기 상태를 확인하지 못했습니다. 잠시 후 다시 확인합니다.')
        }
      }
    }

    if (readSources && sourceVersion === sourceRevision.current && !sourceSaveRef.current) {
      if (sourcesResult.status === 'fulfilled' && sourcesResult.value) {
        setSources(sourcesResult.value)
        setSourcesReady(true)
        setSourcesReadError('')
      } else if (sourcesResult.status === 'rejected') {
        setSourcesReady(false)
        setSourcesReadError('모니터링 소스 상태를 확인하지 못했습니다. 잠시 후 다시 확인합니다.')
      }
    }
  }, [receiveSchedule])

  const cancelActivity = useCallback(() => {
    activitySequence.current++
    statusRequest.current?.abort()
    statusRequest.current = null
  }, [])
  useEffect(() => {
    void refreshActivity()
    const intervalId = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refreshActivity()
    }, 5_000)
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refreshActivity(true)
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      cancelActivity()
      window.clearInterval(intervalId)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [refreshActivity, cancelActivity])

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const runData = await api.getRuns(runPage, 8)
      setRuns(runData.content)
      setRunPages(runData.totalPages)
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
      void refreshActivity(true)
    }
  }

  const runNow = async () => {
    if (runRequestRef.current || !activity || activity.running || !scheduleReady || !sourcesReady || statusChecking || pendingScheduledAt || scheduleSaveRef.current) return
    runRequestRef.current = true
    cancelActivity()
    setRunning(true)
    setError('')
    try {
      const accepted = await api.createRun()
      setActivity({ running: true, runId: accepted.runId, status: accepted.status })
      setToast('모니터링을 시작했어요. 완료될 때까지 추가 실행은 잠깐 기다려 주세요.')
      if (runPage === 0) await load()
      else setRunPage(0)
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409) {
        setActivity(null)
      }
      setError(cause instanceof Error ? cause.message : '모니터링을 시작하지 못했습니다.')
    } finally {
      runRequestRef.current = false
      setRunning(false)
      void refreshActivity(true)
    }
  }

  const changeFrequency = (frequency: MonitoringScheduleFrequency) => {
    scheduleEdited.current = true
    setSchedule((current) => ({
      ...current,
      frequency,
      customDays: frequency === 'CUSTOM' && current.frequency !== 'CUSTOM'
        ? ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']
        : current.customDays,
    }))
  }

  const toggleDay = (day: Weekday) => {
    scheduleEdited.current = true
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
      if (!enabledOnly) scheduleEdited.current = false
      receiveSchedule(updated)
      setToast(enabledOnly
        ? updated.enabled ? '자동 모니터링을 켰어요.' : '자동 모니터링을 껐어요.'
        : '자동 모니터링 일정을 저장했어요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '자동 모니터링 일정을 저장하지 못했습니다.') }
    finally {
      scheduleRevision.current++
      scheduleSaveRef.current = false
      setSavingSchedule(false)
      void refreshActivity(true)
    }
  }

  const cancelPending = async () => {
    if (!pendingScheduledAt || scheduleSaveRef.current) return
    const scheduledAt = pendingScheduledAt
    scheduleSaveRef.current = true
    scheduleRevision.current++
    setCancellingPending(true)
    setPendingError('')
    try {
      const updated = await api.cancelPendingSchedule(scheduledAt)
      receiveSchedule(updated)
      setActivity(null)
      setToast('예약 대기를 취소했어요.')
    } catch (cause) {
      setPendingError(cause instanceof Error ? cause.message : '예약 대기를 취소하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      scheduleRevision.current++
      scheduleSaveRef.current = false
      setCancellingPending(false)
      void refreshActivity(true)
    }
  }

  const scheduleBusy = savingSchedule || cancellingPending
  const pendingMessage = pendingScheduleMessage(
    statusChecking || scheduleBusy || savingSources || running,
    scheduleReady && sourcesReady && activity !== null,
    Boolean(scheduleError || sourcesReadError || activityError),
    activity,
    activeCount,
  )

  return (
    <div className="page">
      <header className="page-header"><div><span className="eyebrow">MONITORING</span><h1>모니터링</h1><p>살펴볼 기관을 관리하고, 필요할 때 바로 모니터링을 시작하세요.</p></div><button className="button button--primary button--run" onClick={runNow} disabled={loading || running || !activity || activity.running || !scheduleReady || !sourcesReady || statusChecking || Boolean(pendingScheduledAt) || scheduleBusy || savingSources || sourceChanges.dirtyCount > 0 || activeCount === 0}>{running ? <RefreshCw size={18} className="spin" /> : <Play size={18} fill="currentColor" />}{running ? '시작 중이에요' : !activity || !scheduleReady || !sourcesReady || statusChecking ? '실행 상태 확인 중' : activity.running ? '모니터링 진행 중' : pendingScheduledAt ? '예약 실행 대기 중' : '지금 모니터링 실행'}</button></header>
      {error && <InlineError message={error} />}
      {activityError && <InlineError message={activityError} />}
      {activity?.running && <p className="muted" role="status">{pendingScheduledAt ? '모니터링이 진행 중입니다. 완료되면 대기 예약이 먼저 실행됩니다.' : '모니터링이 진행 중입니다. 분석과 보고서 생성이 완료되면 다시 실행할 수 있어요.'}</p>}
      <section className="metric-grid">
        <article className="metric-card"><span className="metric-card__icon metric-card__icon--blue"><Building2 size={20} /></span><div><small>등록된 소스</small><strong>{sources.length}<em>개</em></strong><span>{activeCount}개가 확인 중이에요</span></div></article>
        <article className="metric-card"><span className="metric-card__icon metric-card__icon--green"><Activity size={20} /></span><div><small>최근 실행 상태</small><strong className="metric-card__status">{runs[0] ? runLabel[runs[0].status] : '기록 없음'}</strong><span>{runs[0] ? formatDate(runs[0].requestedAt) : '첫 실행을 기다리고 있어요'}</span></div></article>
        <article className="metric-card"><span className="metric-card__icon metric-card__icon--violet"><Clock3 size={20} /></span><div><small>최근 감지 문서</small><strong>{runs[0]?.detectedDocumentCount ?? 0}<em>건</em></strong><span>마지막 실행 기준</span></div></article>
      </section>

      <section className="panel schedule-panel">
        <div className="panel-header"><div><h2>자동 모니터링</h2><p>한국 시간 기준으로 선택한 요일과 시각에 활성 소스를 자동으로 확인하고 Telegram 보고서를 보내요.</p></div><button className={`schedule-switch ${schedule.enabled ? 'schedule-switch--on' : ''}`} role="switch" aria-label="자동 모니터링 사용" aria-checked={schedule.enabled} aria-busy={scheduleBusy} disabled={loading || scheduleBusy || !savedSchedule} onClick={() => { if (savedSchedule) void saveSchedule({ ...savedSchedule, enabled: !savedSchedule.enabled }, true) }}><span>{schedule.enabled ? '사용 중' : '사용 안 함'}</span><i /></button></div>
        {pendingScheduledAt && <div className="schedule-pending">
          <div role="status"><strong><Clock3 size={14} aria-hidden="true" />예약 대기 · <time dateTime={pendingScheduledAt}>{pendingScheduledAt.slice(11, 16)}</time></strong><p>{pendingMessage}</p><small>일정을 변경하거나 끄면 대기가 취소됩니다.</small></div>
          <button type="button" className="button button--subtle" disabled={scheduleBusy} onClick={() => void cancelPending()}>{cancellingPending ? '취소 중...' : '대기 취소'}</button>
        </div>}
        {(scheduleError || pendingError) && <div className="schedule-error" role="alert"><InlineError message={scheduleError || pendingError} /></div>}
        <div className="schedule-panel__body">
          <div className="schedule-field"><span><CalendarDays size={16} />실행 주기</span><div className="schedule-frequency">{([['DAILY', '매일'], ['WEEKDAYS', '평일'], ['CUSTOM', '요일 선택']] as Array<[MonitoringScheduleFrequency, string]>).map(([value, label]) => <button key={value} className={schedule.frequency === value ? 'active' : ''} disabled={loading || scheduleBusy || !savedSchedule} onClick={() => changeFrequency(value)}>{label}</button>)}</div></div>
          {schedule.frequency === 'CUSTOM' && <div className="schedule-field"><span>실행 요일</span><div className="weekday-picker">{weekdays.map((day) => <button key={day.value} className={schedule.customDays.includes(day.value) ? 'active' : ''} disabled={loading || scheduleBusy || !savedSchedule} onClick={() => toggleDay(day.value)}>{day.label}</button>)}</div></div>}
          <label className="schedule-field schedule-time"><span><Clock3 size={16} />실행 시각</span><input type="time" disabled={loading || scheduleBusy || !savedSchedule} value={schedule.executionTime} onChange={(event) => { scheduleEdited.current = true; setSchedule((current) => ({ ...current, executionTime: event.target.value })) }} /></label>
          <button className="button button--primary schedule-save" onClick={() => void saveSchedule()} disabled={loading || scheduleBusy || !savedSchedule}><Save size={16} />{savingSchedule ? '저장 중...' : '일정 저장'}</button>
        </div>
      </section>

      <MonitoringSourcesPanel sources={sources} drafts={sourceDrafts} loading={loading || (!sourcesReady && !sourcesReadError)} saving={savingSources} error={sourceError || sourcesReadError} onChange={changeSource} onSave={saveSources} />

      <section className="panel">
        <div className="panel-header"><div><h2>최근 실행 이력</h2><p>모니터링이 어떻게 진행됐는지 빠르게 확인하세요.</p></div><button className="button button--subtle" onClick={() => { void load(); void refreshActivity(true) }}><RefreshCw size={16} />새로고침</button></div>
        {loading ? <Loading /> : runs.length === 0 ? <EmptyState icon={<Clock3 />} title="아직 실행 이력이 없어요" description="모니터링을 실행하면 처리 과정과 결과가 여기에 쌓여요." /> : <div className="table-wrap"><table className="monitoring-run-table"><thead><tr><th scope="col">실행 시각</th><th scope="col">상태</th><th scope="col">소스</th><th scope="col">감지 문서</th><th scope="col">경고</th></tr></thead><tbody>{runs.map((run) => <tr key={run.runId}><td><strong>{formatDate(run.requestedAt)}</strong><small className="block muted">{run.triggerType === 'MANUAL' ? '수동 실행' : '자동 실행'}</small></td><td><Badge tone={runTone[run.status]}>{runLabel[run.status]}</Badge></td><td>{run.totalSourceCount}개</td><td><b>{run.detectedDocumentCount}</b>건</td><td><RunWarningTooltip runId={run.runId} warningCount={run.warningCount} status={run.status} /></td></tr>)}</tbody></table></div>}
        <Pagination page={runPage} totalPages={runPages} onChange={setRunPage} />
      </section>
      {toast && <Toast message={toast} onClose={() => setToast('')} />}
    </div>
  )
}
