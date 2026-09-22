import ReportBody from '../components/ReportBody'
import { useToast } from '../hooks/useToast'
import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ArrowUpRight, Bot, Check, ChevronDown, Copy, Link2, RefreshCw, Send, SlidersHorizontal, Users, X } from 'lucide-react'
import { api } from '../api/client'
import type { TelegramConnection, TelegramDeliveryStatus, TelegramRecipient, TelegramRecipientDelivery, TelegramReport, TelegramReportDetail, TelegramSettings } from '../api/types'
import { Badge, EmptyState, InlineError, Loading, Pagination, Toast } from '../components/ui'
import './TelegramPage.css'
import DeliveryDialog from '../components/DeliveryDialog'
import { ChannelAutomation, ChannelRecipients, ChannelRecipient } from '../components/DeliveryChannelPanel'

const labels: Record<TelegramDeliveryStatus, string> = {
  SENT: '발송 완료', PARTIAL: '일부 실패', FAILED: '발송 실패', SENDING: '발송 중',
  NOT_SENT: '발송 기록 없음', PREPARING: '생성 중', REPORT_FAILED: '생성 실패',
}
const tones: Record<TelegramDeliveryStatus, string> = {
  SENT: 'success', PARTIAL: 'warning', FAILED: 'danger', SENDING: 'info',
  NOT_SENT: 'neutral', PREPARING: 'info', REPORT_FAILED: 'danger',
}
const telegramBotUrl = 'https://t.me/govInsight11_bot'
const date = (v: string | null) => v ? new Intl.DateTimeFormat('ko-KR', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
}).format(new Date(v)) : '—'
const messageOf = (e: unknown) => e instanceof Error ? e.message : '요청을 처리하지 못했습니다.'
const displayName = (r: { name: string | null; chatId: string }) => r.name || r.chatId

function Dialog({ title, children, onClose, busy = false }: {
  title: string; children: ReactNode; onClose: () => void; busy?: boolean
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useEffect(() => { const d = ref.current; d?.showModal(); return () => d?.close() }, [])
  return <dialog ref={ref} className="telegram-dialog" aria-labelledby={titleId}
    onCancel={e => { e.preventDefault(); if (!busy) onClose() }}>
    <header><h2 id={titleId}>{title}</h2><button className="icon-button" aria-label="닫기" disabled={busy} onClick={onClose}><X size={20} /></button></header>
    {children}
  </dialog>
}
type Editor = { index: number | null; recipient: TelegramRecipient }
type SendTarget = { recipient: TelegramRecipient; delivery?: TelegramRecipientDelivery }

export default function TelegramPage({ embedded = false, onSettingsChange, onNotify }: { embedded?: boolean; onSettingsChange?: (settings: TelegramSettings) => void; onNotify?: (message: string) => void }) {
  const [settings, setSettings] = useState<TelegramSettings | null>(null)
  const [settingsError, setSettingsError] = useState('')
  const [settingsLoading, setSettingsLoading] = useState(true)
  const [connections, setConnections] = useState<Record<string, TelegramConnection>>({})
  const [busy, setBusy] = useState(false)
  const actionRef = useRef(false)
  const [actionError, setActionError] = useState('')
  const [showSettings, setShowSettings] = useState(false)
  const [setupFeedback, setSetupFeedback] = useState('')
  const [toast, setLocalToast] = useToast()
  const setToast = onNotify ?? setLocalToast
  const [editor, setEditor] = useState<Editor | null>(null)
  const [removing, setRemoving] = useState<TelegramRecipient | null>(null)
  const [sendTarget, setSendTarget] = useState<SendTarget | null>(null)
  const [reports, setReports] = useState<TelegramReport[]>([])
  const [historyError, setHistoryError] = useState('')
  const [historyLoading, setHistoryLoading] = useState(true)
  const [page, setPage] = useState(0)
  const [pages, setPages] = useState(0)
  const [total, setTotal] = useState(0)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [status, setStatus] = useState<TelegramDeliveryStatus | ''>('')
  const [filters, setFilters] = useState({ from: '', to: '', status: '' as TelegramDeliveryStatus | '' })
  const sequence = useRef(0)
  const invalidate = useCallback(() => { sequence.current++ }, [])
  const [detailId, setDetailId] = useState<number | null>(null)
  const [detail, setDetail] = useState<TelegramReportDetail | null>(null)
  const [detailError, setDetailError] = useState('')
  const recipients = settings?.recipients || []
  const activeCount = recipients.filter(r => r.enabled).length
  const bot = Object.values(connections).find(c => c.botConnected)

  const loadSettings = useCallback(async () => {
    setSettingsLoading(true); setSettingsError('')
    try { const data = await api.getTelegramSettings(); setSettings(data); onSettingsChange?.(data); setConnections({}) }
    catch (e) { setSettingsError(messageOf(e)) }
    finally { setSettingsLoading(false) }
  }, [onSettingsChange])
  useEffect(() => { void loadSettings() }, [loadSettings])
  const loadHistory = useCallback(async (background = false) => {
    const id = ++sequence.current
    if (!background) { setHistoryLoading(true); setHistoryError('') }
    try {
      const data = await api.getTelegramReports(page, filters.from, filters.to, filters.status)
      if (id !== sequence.current) return
      setReports(data.content); setPages(data.totalPages); setTotal(data.totalElements)
      if (data.totalPages && page >= data.totalPages) setPage(data.totalPages - 1)
    } catch (e) { if (!background && id === sequence.current) setHistoryError(messageOf(e)) }
    finally { if (id === sequence.current) setHistoryLoading(false) }
  }, [page, filters])
  useEffect(() => {
    if (embedded) return
    void loadHistory()
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible' && !actionRef.current) void loadHistory(true)
    }, 10000)
    return () => { window.clearInterval(timer); invalidate() }
  }, [loadHistory, invalidate, embedded])
  useEffect(() => {
    if (detailId === null) return
    const controller = new AbortController()
    api.getTelegramReport(detailId, controller.signal).then(data => {
      if (!controller.signal.aborted) setDetail(data)
    }).catch(e => { if (!controller.signal.aborted) setDetailError(messageOf(e)) })
    return () => controller.abort()
  }, [detailId])

  const runAction = async (action: () => Promise<void>) => {
    if (actionRef.current) return
    actionRef.current = true; setBusy(true); setActionError('')
    try { await action() } catch (e) { setActionError(messageOf(e)) }
    finally { actionRef.current = false; setBusy(false) }
  }
  const persist = async (nextRecipients: TelegramRecipient[], enabled = settings?.enabled || false) => {
    if (!settings) return
    const data = await api.updateTelegramSettings({
      version: settings.version, enabled, recipients: nextRecipients,
    })
    setSettings(data); onSettingsChange?.(data); setSettingsError(''); setConnections({})
  }
  const openSenderSettings = () => { setActionError(''); setSetupFeedback(''); setShowSettings(true) }
  const toggleDelivery = () => {
    if (!settings || actionRef.current) return
    if (!settings.enabled && !settings.botConfigured) { openSenderSettings(); return }
    void runAction(async () => {
      await persist(recipients, !settings.enabled)
      setToast(settings.enabled ? '텔레그램 자동 발송을 껐어요.' : '텔레그램 자동 발송을 켰어요.')
    })
  }
  const refreshSender = () => void runAction(async () => {
    setSetupFeedback('')
    const data = await api.getTelegramSettings()
    setSettings(data); onSettingsChange?.(data); setConnections({})
    setSetupFeedback(data.botConfigured ? '봇 설정을 확인했어요. 테스트 메시지로 수신 여부를 확인해 주세요.' : '아직 봇이 설정되지 않았어요. 서비스 관리자에게 설정을 요청해 주세요.')
  })
  const saveEditor = () => void runAction(async () => {
    if (!editor) return
    const r = { ...editor.recipient, chatId: editor.recipient.chatId.trim(), name: editor.recipient.name.trim() }
    const next = editor.index === null ? [...recipients, r] : recipients.map((old, i) => i === editor.index ? r : old)
    await persist(next)
    setEditor(null); setToast('수신자를 저장했어요.')
  })
  const check = (r: TelegramRecipient) => void runAction(async () => {
    const result = await api.checkTelegramConnection(r.chatId)
    setConnections(current => ({ ...current, [r.chatId]: result }))
  })
  const send = () => void runAction(async () => {
    if (!sendTarget) return
    if (sendTarget.delivery) {
      const result = await api.retryTelegramReport(sendTarget.delivery.deliveryId, sendTarget.recipient.chatId, sendTarget.delivery.attemptCount)
      if (detailId !== null) setDetail(await api.getTelegramReport(detailId))
      await loadHistory()
      if (result.status !== 'SENT') {
        setSendTarget({ ...sendTarget, delivery: result })
        setActionError(result.errorMessage || '발송에 실패했습니다.'); return
      }
    } else {
      const result = await api.sendTelegramTest(sendTarget.recipient.chatId)
      if (!result.sent) { setActionError(result.message); return }
    }
    setSendTarget(null); setToast('메시지를 보냈어요.')
  })
  const copyBotLink = async () => {
    try {
      await navigator.clipboard.writeText(telegramBotUrl)
      setToast('봇 링크를 복사했어요. 보고서를 받을 분에게 보내 주세요.')
    } catch {
      setToast('복사하지 못했어요. 아래 봇 링크를 직접 복사해 주세요.')
    }
  }
  const openDetail = (id: number) => { setDetail(null); setDetailError(''); setDetailId(id) }

  return <div className={embedded ? "telegram-page delivery-embedded" : "page telegram-page"}>
    {!embedded && <header className="page-header">
      <div><span className="eyebrow">TELEGRAM</span><h1>텔레그램</h1><p>보고서를 받을 사람을 관리하고, 발송 결과를 확인하세요.</p></div>
      {settings && <span className="telegram-bot"><Bot size={16} />{bot?.botUsername ? '@' + bot.botUsername : settings.botConfigured ? '봇 설정됨' : '봇 미설정'}</span>}
    </header>}
    {settingsError && <InlineError message={settingsError} />}
    {settingsLoading ? <Loading /> : !settings ? <section className="panel telegram-reload">
      <button className="button button--secondary" onClick={() => void loadSettings()}>설정 다시 불러오기</button>
    </section> : <div className="channel-panel">
      <ChannelAutomation channel="텔레그램" enabled={settings.enabled} busy={busy}
        setupHint={!settings.botConfigured ? '보내는 봇을 설정하면 켤 수 있어요.' : undefined} onToggle={toggleDelivery} onSettings={openSenderSettings} />
      <ChannelRecipients count={recipients.length} selected={activeCount} busy={busy}
        onAdd={() => { setActionError(''); setEditor({ index: null, recipient: { name: '', chatId: '', enabled: true } }) }}>
    {actionError && !editor && !removing && !sendTarget && !showSettings && <div className="telegram-action-error"><InlineError message={actionError} />
      <button className="button button--subtle" disabled={busy} onClick={() => { setActionError(''); void loadSettings() }}>설정 새로고침</button></div>}

        {recipients.length === 0 ? <EmptyState icon={<Users />} title="보고서를 받을 사람을 추가하세요" description="텔레그램 봇에서 받은 채팅 ID를 등록하세요." /> : recipients.map((r, i) => <ChannelRecipient key={r.chatId}
          name={r.name} address={r.chatId} enabled={r.enabled} busy={busy} icon={<Send size={18} />}
          onToggle={() => void runAction(async () => { await persist(recipients.map((old, n) => n === i ? { ...old, enabled: !old.enabled } : old)) })}
          onTest={() => { if (!settings.botConfigured) { openSenderSettings(); return } setActionError(''); setSendTarget({ recipient: r }) }}
          onEdit={() => { setActionError(''); setEditor({ index: i, recipient: { ...r } }) }}
          onDelete={() => { setActionError(''); setRemoving(r) }}
          feedback={connections[r.chatId] && <span className={connections[r.chatId].chatConnected ? 'telegram-connected' : 'telegram-check-error'}>{connections[r.chatId].chatConnected ? <><Check size={12} />연결 확인됨</> : connections[r.chatId].message}</span>}
          extraAction={<button type="button" className="icon-button" aria-label={displayName(r) + ' 연결 확인'} title="연결 확인" disabled={busy} onClick={() => { if (!settings.botConfigured) { openSenderSettings(); return } check(r) }}><Link2 size={16} /></button>} />)}
      </ChannelRecipients>
    </div>}

    {showSettings && <DeliveryDialog title="텔레그램 발송 설정" busy={busy} onClose={() => setShowSettings(false)}>
      <p className="email-setup-intro">{settings?.botConfigured ? '등록된 봇으로 선택한 사람에게 보고서를 보냅니다.' : '서비스 관리자가 서버에 텔레그램 봇을 설정하면 사용할 수 있어요.'}</p>
      <div className="delivery-provider"><span className="channel-recipient-avatar"><Bot size={22} /></span><div><strong>{settings?.botConfigured ? '보내는 봇 설정됨' : '아직 등록된 봇이 없어요'}</strong><p>받는 사람이 봇에서 시작(Start)을 눌러야 메시지를 받을 수 있어요.</p></div></div>
      <details className="email-setup-guide channel-setup-guide"><summary>수신자 등록 방법<ChevronDown size={14} /></summary>
        <div className="telegram-help-link">
          <span><Send size={15} />우리 봇</span>
          <a href={telegramBotUrl} target="_blank" rel="noopener noreferrer">t.me/govInsight11_bot<ArrowUpRight size={15} /></a>
          <button type="button" onClick={() => void copyBotLink()}><Copy size={14} />링크 복사</button>
        </div>
        <ol className="telegram-help-steps">
          <li><div><strong><span>봇 링크를 보내 주세요</span></strong><p>위의 <b>링크 복사</b>를 눌러 보고서를 받을 분에게 보내고, <b>시작(Start)</b>을 눌러 달라고 안내해 주세요.</p></div></li>
          <li><div><strong><span>채팅 ID를 전달받으세요</span></strong><p>봇이 답장으로 알려 주는 <b>‘내 채팅 ID’ 숫자</b>를 받아 주세요. 전화번호나 @아이디와는 달라요.</p></div></li>
          <li><div><strong><span>이름과 채팅 ID를 등록하세요</span></strong><p><b>수신자 추가</b>에서 입력하면 끝이에요. 받는 사람 목록의 체크박스로 보고서를 받을지 정할 수 있어요.</p></div></li>
        </ol>
        <p className="telegram-help-note">답장이 오지 않으면 서버가 켜져 있는지 확인한 뒤, 봇에게 <code>/start</code>를 다시 보내 주세요.</p>
        <p className="telegram-help-note">최대 20곳까지 등록할 수 있어요. 그룹은 봇을 초대한 뒤 그룹 채팅 ID를 등록해 주세요.</p>
      </details>
      {setupFeedback && <p className="email-setup-feedback" role="status">{setupFeedback}</p>}
      {actionError && <InlineError message={actionError} />}
      <footer className="email-setup-footer"><button type="button" className="button button--subtle" disabled={busy} onClick={() => setShowSettings(false)}>{settings?.botConfigured ? '완료' : '나중에'}</button>
        <button type="button" className="button button--secondary" disabled={busy} onClick={refreshSender}><RefreshCw size={14} />{busy ? '처리 중...' : '설정 상태 다시 확인'}</button>
        {settings?.botConfigured && !settings.enabled && <button type="button" className="button button--primary" disabled={busy} onClick={() => void runAction(async () => { await persist(recipients, true); setShowSettings(false); setToast('텔레그램 자동 발송을 켰어요.') })}>자동 발송 켜기</button>}
      </footer>
    </DeliveryDialog>}

    {!embedded && <section className="panel telegram-history">
      <div className="panel-header"><h2>발송 내역 <span className="telegram-count">{total}</span></h2>
        <button className="icon-button" aria-label="발송 내역 새로고침" title="새로고침" disabled={historyLoading} onClick={() => void loadHistory()}><RefreshCw size={17} /></button></div>
      <details className="telegram-filter-details"><summary><SlidersHorizontal size={14} />필터{filters.status && ' · ' + labels[filters.status]}{(filters.from || filters.to) && ' · 기간 설정'}<ChevronDown size={13} /></summary>
        <form className="telegram-filters" onSubmit={e => {
          e.preventDefault()
          if (from && to && from > to) { setHistoryError('시작 날짜를 확인해 주세요.'); return }
          setPage(0); setFilters({ from, to, status })
        }}>
          <label>시작일<input type="date" value={from} max={to || undefined} onChange={e => setFrom(e.target.value)} /></label>
          <label>종료일<input type="date" value={to} min={from || undefined} onChange={e => setTo(e.target.value)} /></label>
          <label>발송 상태<select aria-label="발송 상태" value={status} onChange={e => setStatus(e.target.value as TelegramDeliveryStatus | '')}>
            <option value="">전체</option>{Object.entries(labels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
          </select></label>
          <button className="button button--secondary">조회</button>
          <button type="button" className="button button--subtle" onClick={() => { setFrom(''); setTo(''); setStatus(''); setPage(0); setFilters({ from: '', to: '', status: '' }) }}>초기화</button>
        </form>
      </details>
      {historyError && <InlineError message={historyError} />}
      {historyLoading ? <Loading /> : reports.length === 0 ? <EmptyState icon={<Send />} title="발송 내역이 없어요" description="" /> :
        <div className="telegram-report-list">{reports.map(r => <button key={r.reportId} className="telegram-report-row" onClick={() => openDetail(r.reportId)}>
          <div><strong>{r.title || '보고서 #' + r.runId}</strong><small>{date(r.createdAt)}</small></div>
          <div className="telegram-report-result"><Badge tone={tones[r.status]}>{labels[r.status]}</Badge>
            {r.recipientCount > 0 && <small>{r.sentCount}/{r.recipientCount}명 발송</small>}</div><ArrowUpRight size={17} />
        </button>)}</div>}
      <Pagination page={page} totalPages={pages} onChange={setPage} />
    </section>}

    {editor && <Dialog title={editor.index === null ? '수신자 추가' : '수신자 수정'} busy={busy} onClose={() => setEditor(null)}>
      <p className="channel-registration-hint">받는 사람이 <a href={telegramBotUrl} target="_blank" rel="noopener noreferrer">텔레그램 봇</a>에서 시작(Start)을 누르면 채팅 ID를 확인할 수 있어요.</p>
      <form onSubmit={e => { e.preventDefault(); saveEditor() }}>
        <div className="telegram-editor">
          <label>이름<input autoFocus value={editor.recipient.name} maxLength={100} placeholder="예: 김민수" disabled={busy}
            onChange={e => setEditor({ ...editor, recipient: { ...editor.recipient, name: e.target.value } })} /></label>
          <label>채팅 ID<input required value={editor.recipient.chatId} maxLength={100} placeholder="숫자 ID 또는 @채널이름"
            pattern="(-?[1-9][0-9]{0,19}|@[A-Za-z][A-Za-z0-9_]{4,31})" disabled={busy}
            onChange={e => setEditor({ ...editor, recipient: { ...editor.recipient, chatId: e.target.value } })} /></label>
        </div>
        {actionError && <InlineError message={actionError} />}
        <footer><button type="button" className="button button--subtle" disabled={busy} onClick={() => setEditor(null)}>취소</button>
          <button className="button button--primary" disabled={busy}>{busy ? '저장 중...' : '저장'}</button></footer>
      </form>
    </Dialog>}
    {removing && <Dialog title="수신자 삭제" busy={busy} onClose={() => setRemoving(null)}>
      <p><strong>{displayName(removing)}</strong>님을 수신자 목록에서 삭제할까요?</p>
      {actionError && <InlineError message={actionError} />}
      <footer><button className="button button--subtle" disabled={busy} onClick={() => setRemoving(null)}>취소</button>
        <button className="button button--primary" disabled={busy} onClick={() => void runAction(async () => {
          await persist(recipients.filter(r => r.chatId !== removing.chatId)); setRemoving(null); setToast('수신자를 삭제했어요.')
        })}>삭제</button></footer>
    </Dialog>}
    {detailId !== null && <Dialog title="발송 상세" busy={busy} onClose={() => setDetailId(null)}>
      {detailError ? <InlineError message={detailError} /> : !detail ? <Loading /> : <>
        <h3>{detail.report.title}</h3>
        <div className="telegram-detail-meta"><Badge tone={tones[detail.report.status]}>{labels[detail.report.status]}</Badge><span>{date(detail.report.generatedAt)}</span></div>
        <div className="telegram-deliveries">{detail.deliveries.length === 0 ? <p>수신자별 기록이 없습니다.</p> : detail.deliveries.map(d => {
          const current = recipients.find(r => r.chatId === d.chatId && r.enabled)
          return <div className="telegram-delivery-row" key={d.deliveryId}>
            <div><strong>{displayName(d)}</strong><small>{d.chatId} · {date(d.sentAt || d.attemptedAt)}</small>{d.errorMessage && <p>{d.errorMessage}</p>}</div>
            <Badge tone={d.status === 'SENT' ? 'success' : d.status === 'FAILED' ? 'danger' : 'info'}>{d.status === 'SENT' ? '완료' : d.status === 'FAILED' ? '실패' : '발송 중'}</Badge>
            {d.status === 'FAILED' && <button className="button button--secondary" disabled={busy || !current || !settings?.enabled || !settings.botConfigured}
              title={!current ? '등록된 수신자의 수신을 켜 주세요.' : !settings?.enabled ? '텔레그램 발송을 켜 주세요.' : '이 수신자에게만 재전송'}
              onClick={() => { if (current) { setActionError(''); setSendTarget({ recipient: current, delivery: d }) } }}>재전송</button>}
          </div>
        })}</div>
        {detail.report.errorMessage && <InlineError message={detail.report.errorMessage} />}
        <details className="telegram-body-details"><summary>보고서 내용<ChevronDown size={14} /></summary><ReportBody body={detail.body || '본문이 없습니다.'} /></details>
        <footer><Link className="button button--subtle" to={'/documents?runId=' + detail.report.runId}>게시글 보기<ArrowUpRight size={15} /></Link></footer>
      </>}
    </Dialog>}
    {sendTarget && <Dialog title={sendTarget.delivery ? '보고서 재전송' : '테스트 발송'} busy={busy} onClose={() => setSendTarget(null)}>
      <div className="telegram-send-target"><strong>{displayName(sendTarget.recipient)}</strong><span>{sendTarget.recipient.chatId}</span></div>
      <p>{sendTarget.delivery ? '이 수신자에게만 다시 보냅니다. 이미 도착한 메시지가 없는지 채팅을 확인해 주세요.' : '이 수신자에게 테스트 메시지 한 건을 보냅니다.'}</p>
      {actionError && <InlineError message={actionError} />}
      <footer><button className="button button--subtle" disabled={busy} onClick={() => setSendTarget(null)}>취소</button>
        <button className="button button--primary" disabled={busy} onClick={() => void send()}><Send size={15} />{busy ? '보내는 중...' : '보내기'}</button></footer>
    </Dialog>}
    {toast && <Toast message={toast} onClose={() => setToast('')} />}
  </div>
}
