import ReportBody from '../components/ReportBody'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowUpRight, ChevronDown, FileText, Mail, RefreshCw, Send, SlidersHorizontal } from 'lucide-react'
import { api } from '../api/client'
import type { ChannelDeliverySummary, DeliveryChannel, EmailSettings, ReportDelivery, ReportDeliveryDetail, TelegramDeliveryStatus, TelegramSettings } from '../api/types'
import { Badge, EmptyState, InlineError, Loading, Pagination, Toast } from '../components/ui'
import { useToast } from '../hooks/useToast'
import DeliveryDialog from '../components/DeliveryDialog'
import EmailRecipients from '../components/EmailRecipients'
import TelegramPage from './TelegramPage'
import './ReportDeliveryPage.css'

const labels: Record<TelegramDeliveryStatus, string> = { SENT: '발송 완료', PARTIAL: '일부 실패', FAILED: '발송 실패', SENDING: '발송 중', NOT_SENT: '발송 기록 없음', PREPARING: '생성 중', REPORT_FAILED: '생성 실패' }
const date = (value: string | null) => value ? new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(/(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : value + '+09:00')) : '—'
const messageOf = (e: unknown) => e instanceof Error ? e.message : '요청을 처리하지 못했습니다.'
type RetryTarget = { channel: 'TELEGRAM' | 'EMAIL'; deliveryId: number; address: string; name: string | null; attemptCount: number }

function ChannelResult({ channel, summary }: { channel: 'telegram' | 'email'; summary: ChannelDeliverySummary }) {
  const Icon = channel === 'email' ? Mail : Send
  return <span className={'delivery-result delivery-result--' + summary.status.toLowerCase()}><Icon size={12} /><span>{channel === 'email' ? '이메일' : '텔레그램'}</span><strong>{summary.status === 'SENT' && summary.recipientCount ? `${summary.sentCount}/${summary.recipientCount} 완료` : labels[summary.status]}</strong></span>
}

export default function ReportDeliveryPage() {
  const [channel, setChannel] = useState<'telegram' | 'email'>('telegram')
  const [telegram, setTelegram] = useState<TelegramSettings | null>(null)
  const [email, setEmail] = useState<EmailSettings | null>(null)
  const [reports, setReports] = useState<ReportDelivery[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [page, setPage] = useState(0)
  const [pages, setPages] = useState(0)
  const [total, setTotal] = useState(0)
  const [historyChannel, setHistoryChannel] = useState<DeliveryChannel>('ALL')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [status, setStatus] = useState<TelegramDeliveryStatus | ''>('')
  const [filters, setFilters] = useState({ from: '', to: '', status: '' as TelegramDeliveryStatus | '' })
  const requestSequence = useRef(0)
  const invalidateHistory = useCallback(() => { requestSequence.current++ }, [])
  const [detailId, setDetailId] = useState<number | null>(null)
  const [detail, setDetail] = useState<ReportDeliveryDetail | null>(null)
  const [detailError, setDetailError] = useState('')
  const [retry, setRetry] = useState<RetryTarget | null>(null)
  const [retryError, setRetryError] = useState('')
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [toast, setToast] = useToast()
  const loadHistory = useCallback(async (background = false) => {
    const sequence = ++requestSequence.current
    if (!background) setLoading(true)
    try {
      const result = await api.getReportDeliveries(page, filters.from, filters.to, historyChannel, filters.status)
      if (sequence !== requestSequence.current) return
      setReports(result.content); setPages(result.totalPages); setTotal(result.totalElements); setError('')
      if (result.totalPages && page >= result.totalPages) setPage(result.totalPages - 1)
    } catch (e) { if (sequence === requestSequence.current) setError(messageOf(e)) }
    finally { if (sequence === requestSequence.current) setLoading(false) }
  }, [page, filters, historyChannel])
  useEffect(() => {
    void loadHistory()
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible' && !busyRef.current) void loadHistory(true) }, 10000)
    return () => { invalidateHistory(); window.clearInterval(timer) }
  }, [loadHistory, invalidateHistory])
  useEffect(() => {
    if (detailId === null) return
    const controller = new AbortController()
    api.getReportDelivery(detailId, controller.signal).then(result => { if (!controller.signal.aborted) setDetail(result) })
      .catch(e => { if (!controller.signal.aborted) setDetailError(messageOf(e)) })
    return () => controller.abort()
  }, [detailId])
  const openDetail = (id: number) => { setDetail(null); setDetailError(''); setDetailId(id) }
  const canRetry = (target: RetryTarget) => target.channel === 'EMAIL'
    ? email?.enabled && email.configured && email.recipients.some(r => r.enabled && r.address === target.address)
    : telegram?.enabled && telegram.botConfigured && telegram.recipients.some(r => r.enabled && r.chatId === target.address)
  const sendRetry = async () => {
    if (!retry || busyRef.current) return
    busyRef.current = true; setBusy(true); setRetryError('')
    try {
      const result = retry.channel === 'EMAIL' ? await api.retryEmailReport(retry.deliveryId, retry.address, retry.attemptCount) : await api.retryTelegramReport(retry.deliveryId, retry.address, retry.attemptCount)
      if (result.status === 'SENT') { setRetry(null); setToast(retry.channel === 'EMAIL' ? '메일 서버에 전달했어요. 수신함을 확인해 주세요.' : '보고서를 다시 보냈어요.') }
      else { setRetry({ ...retry, attemptCount: result.attemptCount }); setRetryError(result.errorMessage || '발송에 실패했습니다.') }
      if (detailId !== null) {
        try { setDetail(await api.getReportDelivery(detailId)) }
        catch (e) { setDetailError('발송 요청을 처리했지만 내역을 새로 불러오지 못했습니다. ' + messageOf(e)) }
      }
      await loadHistory()
    } catch (e) { setRetryError(messageOf(e)) }
    finally { busyRef.current = false; setBusy(false) }
  }
  const targets = detail ? [
    ...detail.telegramDeliveries.map(d => ({ ...d, address: d.chatId, channel: 'TELEGRAM' as const })),
    ...detail.emailDeliveries.map(d => ({ ...d, channel: 'EMAIL' as const })),
  ] : []
  return <div className="page delivery-page">
    <header className="page-header delivery-page-header"><div><span className="eyebrow">REPORT DELIVERY</span><h1>필요한 소식, 원하는 곳으로<span className="delivery-heading-dot">.</span></h1><p>텔레그램과 이메일로 모니터링 보고서를 받아보세요.</p></div><span className="delivery-header-symbol" aria-hidden="true"><Send size={26} /><span><Mail size={16} /></span></span></header>
    <section className="delivery-channels" aria-label="보고서 수신자 관리">
      <div className="delivery-section-heading"><div><h2>발송 채널</h2><p>보고서를 보낼 방법을 선택하세요.</p></div></div>
      <div className="delivery-channel-tabs" role="tablist" aria-label="발송 채널">
        {(['telegram', 'email'] as const).map(key => {
          const Icon = key === 'telegram' ? Send : Mail
          return <button key={key} id={'delivery-tab-' + key} role="tab" aria-selected={channel === key} aria-controls={'delivery-panel-' + key} tabIndex={channel === key ? 0 : -1}
            onKeyDown={event => {
              if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
              event.preventDefault()
              const next = event.key === 'Home' ? 'telegram' : event.key === 'End' ? 'email' : key === 'telegram' ? 'email' : 'telegram'
              setChannel(next); document.getElementById('delivery-tab-' + next)?.focus()
            }} className={'delivery-channel-tab ' + (channel === key ? 'is-selected' : '')} onClick={() => setChannel(key)}>
            <Icon size={18} /><span>{key === 'telegram' ? '텔레그램' : '이메일'}</span>
          </button>
        })}
      </div>
      <div id="delivery-panel-telegram" role="tabpanel" aria-labelledby="delivery-tab-telegram" hidden={channel !== 'telegram'}><TelegramPage embedded onSettingsChange={setTelegram} onNotify={setToast} /></div>
      <div id="delivery-panel-email" role="tabpanel" aria-labelledby="delivery-tab-email" hidden={channel !== 'email'}><EmailRecipients onSettingsChange={setEmail} onNotify={setToast} /></div>
    </section>
    <section className="panel delivery-history"><div className="panel-header"><div className="delivery-history-heading"><span className="delivery-history-icon"><FileText size={18} /></span><div><h2>발송 내역 <span className="telegram-count">{total}</span></h2><p>보고서별로 각 채널의 발송 결과를 확인하세요.</p></div></div><button className="icon-button" aria-label="발송 내역 새로고침" disabled={loading} onClick={() => void loadHistory()}><RefreshCw size={16} /></button></div>
      <div className="delivery-history-controls"><div className="delivery-history-tabs" role="group" aria-label="발송 내역 채널">{(['ALL', 'TELEGRAM', 'EMAIL'] as const).map(key => <button key={key} aria-pressed={historyChannel === key} onClick={() => { setPage(0); setHistoryChannel(key) }}>{key === 'ALL' ? '모든 채널' : key === 'EMAIL' ? '이메일' : '텔레그램'}</button>)}</div><span className="delivery-history-caption">최근 발송순</span></div>
      <details className="telegram-filter-details"><summary><SlidersHorizontal size={13} />상세 필터{filters.status && ' · ' + labels[filters.status]}{(filters.from || filters.to) && ' · 기간 설정'}<ChevronDown size={13} /></summary><form className="telegram-filters" onSubmit={event => { event.preventDefault(); if (from && to && from > to) { setError('시작 날짜를 확인해 주세요.'); return } setPage(0); setFilters({ from, to, status }) }}>
        <label>시작일<input type="date" value={from} max={to || undefined} onChange={e => setFrom(e.target.value)} /></label><label>종료일<input type="date" value={to} min={from || undefined} onChange={e => setTo(e.target.value)} /></label><label>발송 상태<select value={status} onChange={e => setStatus(e.target.value as TelegramDeliveryStatus | '')}><option value="">전체 상태</option>{Object.entries(labels).map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select></label><button className="button button--secondary">조회</button><button type="button" className="button button--subtle" onClick={() => { setFrom(''); setTo(''); setStatus(''); setPage(0); setFilters({ from: '', to: '', status: '' }) }}>초기화</button>
      </form></details>
      {error && <div className="delivery-load-error"><InlineError message={error} /></div>}
      {loading ? <Loading /> : !reports.length ? <EmptyState icon={<FileText />} title="아직 발송 내역이 없어요" description="모니터링이 완료되면 보고서와 채널별 발송 결과가 여기에 표시됩니다." /> : <div>{reports.map(report => <button className="delivery-report-row" key={report.reportId} onClick={() => openDetail(report.reportId)}><span className="delivery-file-icon"><FileText size={19} /></span><span className="delivery-report-info"><strong>{report.title || '보고서 #' + report.runId}</strong><small>{date(report.createdAt)}</small><span className="delivery-report-results">{historyChannel !== 'EMAIL' && <ChannelResult channel="telegram" summary={report.telegram} />}{historyChannel !== 'TELEGRAM' && <ChannelResult channel="email" summary={report.email} />}</span></span><span className="delivery-report-open">상세 보기<ArrowUpRight size={15} /></span></button>)}</div>}
      <Pagination page={page} totalPages={pages} onChange={setPage} />
    </section>
    {detailId !== null && <DeliveryDialog title="보고서 발송 상세" busy={busy} onClose={() => setDetailId(null)}>
      {detailError && <InlineError message={detailError} />}{!detail ? !detailError && <Loading /> : <><h3>{detail.report.title || '보고서 #' + detail.report.runId}</h3><div className="delivery-report-results"><ChannelResult channel="telegram" summary={detail.report.telegram} /><ChannelResult channel="email" summary={detail.report.email} /></div><p>{date(detail.report.generatedAt)}</p>
        {(detail.report.telegram.errorMessage || detail.report.email.errorMessage) && <InlineError message={detail.report.telegram.errorMessage || detail.report.email.errorMessage || ''} />}
        <div className="telegram-deliveries">{!targets.length ? <p>수신자별 발송 기록이 없습니다.</p> : targets.map(target => <div className="telegram-delivery-row" key={target.channel + target.deliveryId}>
          <span className={'delivery-target-channel ' + (target.channel === 'EMAIL' ? 'is-email' : '')}>{target.channel === 'EMAIL' ? <Mail size={16} /> : <Send size={16} />}</span><div><strong>{target.name || target.address}</strong><small>{target.channel === 'EMAIL' ? '이메일' : '텔레그램'} · {target.address}</small><small>{date(target.sentAt || target.attemptedAt)} · {target.attemptCount}회 시도</small>{target.errorMessage && <p>{target.errorMessage}</p>}</div><Badge tone={target.status === 'SENT' ? 'success' : target.status === 'FAILED' ? 'danger' : 'info'}>{target.status === 'SENT' ? '완료' : target.status === 'FAILED' ? '실패' : '발송 중'}</Badge>
          {target.status === 'FAILED' && <button className="button button--secondary" disabled={busy || !canRetry(target)} title="현재 채널과 수신자 발송 설정이 켜져 있어야 재전송할 수 있습니다." onClick={() => { setRetryError(''); setRetry(target) }}>재전송</button>}
        </div>)}</div><details className="telegram-body-details"><summary>보고서 내용<ChevronDown size={14} /></summary><ReportBody body={detail.body || '본문이 없습니다.'} /></details><p className="delivery-detail-note">이메일 완료는 메일 서버가 전송을 접수한 상태입니다. 수신함 도착·읽음 여부는 별도로 확인해 주세요.</p><footer><Link className="button button--subtle" to={'/documents?runId=' + detail.report.runId}>게시글 보기<ArrowUpRight size={15} /></Link></footer></>}
    </DeliveryDialog>}
    {retry && <DeliveryDialog title="보고서 재전송" busy={busy} onClose={() => setRetry(null)}><div className="telegram-send-target"><strong>{retry.name || '수신자'}</strong><span>{retry.address}</span></div><p>이 수신자에게만 다시 보냅니다. 이미 도착한 보고서가 없는지 먼저 확인해 주세요.</p>{retryError && <InlineError message={retryError} />}<footer><button className="button button--subtle" disabled={busy} onClick={() => setRetry(null)}>취소</button><button className="button button--primary" disabled={busy} onClick={() => void sendRetry()}><Send size={15} />{busy ? '보내는 중...' : '다시 보내기'}</button></footer></DeliveryDialog>}
    {toast && <Toast message={toast} onClose={() => setToast('')} />}
  </div>
}
