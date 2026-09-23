import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowUpRight, ChevronDown, Mail, RefreshCw, Send } from 'lucide-react'
import { api } from '../api/client'
import type { EmailRecipient, EmailSettings } from '../api/types'
import { EmptyState, InlineError, Loading } from './ui'
import DeliveryDialog from './DeliveryDialog'
import { ChannelAutomation, ChannelRecipients, ChannelRecipient } from './DeliveryChannelPanel'

export default function EmailRecipients({ onSettingsChange, onNotify }: { onSettingsChange: (settings: EmailSettings) => void; onNotify: (message: string) => void }) {
  const [settings, setSettings] = useState<EmailSettings | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [actionError, setActionError] = useState('')
  const [busy, setBusy] = useState(false)
  const action = useRef(false)
  const [editor, setEditor] = useState<{ index: number | null; recipient: EmailRecipient } | null>(null)
  const [removing, setRemoving] = useState<EmailRecipient | null>(null)
  const [testing, setTesting] = useState<EmailRecipient | null>(null)
  const [showSettings, setShowSettings] = useState(false)
  const [setupFeedback, setSetupFeedback] = useState('')
  const setToast = onNotify
  const load = useCallback(async () => {
    setLoading(true); setError('')
    try { const data = await api.getEmailSettings(); setSettings(data); onSettingsChange(data) }
    catch (e) { setError(e instanceof Error ? e.message : '설정을 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }, [onSettingsChange])
  useEffect(() => { void load() }, [load])
  const run = async (fn: () => Promise<void>) => {
    if (action.current) return
    action.current = true; setBusy(true); setActionError('')
    try { await fn() } catch (e) { setActionError(e instanceof Error ? e.message : '저장하지 못했습니다.') }
    finally { action.current = false; setBusy(false) }
  }
  const persist = async (recipients: EmailRecipient[], enabled = settings?.enabled ?? false) => {
    if (!settings) return
    const data = await api.updateEmailSettings({ version: settings.version, enabled, recipients })
    setSettings(data); onSettingsChange(data)
  }
  const openSenderSettings = () => { setActionError(''); setSetupFeedback(''); setShowSettings(true) }
  const toggleDelivery = () => {
    if (!settings || action.current) return
    if (!settings.enabled && !settings.configured) { openSenderSettings(); return }
    void run(async () => {
      await persist(settings.recipients, !settings.enabled)
      setToast(settings.enabled ? '이메일 자동 발송을 껐어요.' : '이메일 자동 발송을 켰어요.')
    })
  }
  const refreshSender = () => void run(async () => {
    setSetupFeedback('')
    const data = await api.getEmailSettings()
    setSettings(data); onSettingsChange(data)
    setSetupFeedback(data.configured ? '발신 계정 설정을 확인했어요. 테스트 메일로 수신 여부를 확인해 주세요.' : '아직 발신 계정이 설정되지 않았어요. 아래 설정 방법을 확인해 주세요.')
  })
  const recipients = settings?.recipients ?? []
  const provider = settings?.provider === 'NAVER' ? '네이버 메일' : 'Gmail'
  return <div className="delivery-embedded">
    {loading ? <Loading /> : !settings ? <div className="delivery-load-error"><InlineError message={error} /><button className="button button--secondary" onClick={() => void load()}>다시 불러오기</button></div> : <div className="channel-panel">
      <ChannelAutomation channel="이메일" enabled={settings.enabled} busy={busy}
        setupHint={!settings.configured ? '보내는 계정을 설정하면 켤 수 있어요.' : undefined} onToggle={toggleDelivery} onSettings={openSenderSettings} />
      <ChannelRecipients count={recipients.length} selected={recipients.filter(r => r.enabled).length} busy={busy}
        onAdd={() => { setActionError(''); setEditor({ index: null, recipient: { name: '', address: '', enabled: true } }) }}>
        {actionError && !editor && !removing && !testing && !showSettings && <div className="delivery-load-error"><InlineError message={actionError} /><button className="button button--subtle" disabled={busy} onClick={() => { setActionError(''); void load() }}>설정 새로고침</button></div>}
        {recipients.length === 0 ? <EmptyState icon={<Mail />} title="보고서를 받을 사람을 추가하세요" description="Gmail, 네이버 등 사용하는 이메일 주소를 등록하세요." /> : recipients.map((recipient, index) => <ChannelRecipient key={recipient.address}
          name={recipient.name} address={recipient.address} enabled={recipient.enabled} busy={busy} icon={<Mail size={18} />}
          onToggle={() => void run(async () => { await persist(recipients.map((r, i) => i === index ? { ...r, enabled: !r.enabled } : r)) })}
          onTest={() => { if (!settings.configured) { openSenderSettings(); return } setActionError(''); setTesting(recipient) }}
          onEdit={() => { setActionError(''); setEditor({ index, recipient: { ...recipient } }) }}
          onDelete={() => { setActionError(''); setRemoving(recipient) }} />)}
      </ChannelRecipients>
    </div>}

    {editor && <DeliveryDialog title={editor.index === null ? '이메일 수신자 추가' : '이메일 수신자 수정'} busy={busy} onClose={() => setEditor(null)}>
      <p className="delivery-dialog-intro">보고서는 각 수신자에게 개별 메일로 전송됩니다.</p>
      <form onSubmit={event => { event.preventDefault(); void run(async () => {
        const recipient = { ...editor.recipient, name: editor.recipient.name.trim(), address: editor.recipient.address.trim().toLowerCase() }
        await persist(editor.index === null ? [...recipients, recipient] : recipients.map((r, i) => i === editor.index ? recipient : r))
        setEditor(null); setToast('수신자를 저장했어요.')
      }) }}><div className="telegram-editor"><label>이름<input autoFocus maxLength={100} placeholder="예: 사업 담당자" value={editor.recipient.name} disabled={busy} onChange={e => setEditor({ ...editor, recipient: { ...editor.recipient, name: e.target.value } })} /></label>
        <label>이메일 주소<input type="email" required maxLength={254} placeholder="name@gmail.com" value={editor.recipient.address} disabled={busy} onChange={e => setEditor({ ...editor, recipient: { ...editor.recipient, address: e.target.value } })} /></label></div>
        {actionError && <InlineError message={actionError} />}<footer><button type="button" className="button button--subtle" disabled={busy} onClick={() => setEditor(null)}>취소</button><button className="button button--primary" disabled={busy}>{busy ? '저장 중...' : '저장'}</button></footer></form>
    </DeliveryDialog>}
    {removing && <DeliveryDialog title="수신자 삭제" busy={busy} onClose={() => setRemoving(null)}><p><strong>{removing.name || removing.address}</strong>님을 수신자 목록에서 삭제할까요?</p>{actionError && <InlineError message={actionError} />}<footer><button className="button button--subtle" disabled={busy} onClick={() => setRemoving(null)}>취소</button><button className="button button--primary" disabled={busy} onClick={() => void run(async () => { await persist(recipients.filter(r => r.address !== removing.address)); setRemoving(null); setToast('수신자를 삭제했어요.') })}>삭제</button></footer></DeliveryDialog>}
    {testing && <DeliveryDialog title="테스트 메일 발송" busy={busy} onClose={() => setTesting(null)}><div className="telegram-send-target"><strong>{testing.name || '이메일 수신자'}</strong><span>{testing.address}</span></div><p>이 주소로 테스트 메일 한 건을 보냅니다.</p>{actionError && <InlineError message={actionError} />}<footer><button className="button button--subtle" disabled={busy} onClick={() => setTesting(null)}>취소</button><button className="button button--primary" disabled={busy} onClick={() => void run(async () => { const result = await api.sendEmailTest(testing.address); if (!result.sent) { setActionError(result.message); return } setTesting(null); setToast(result.message) })}><Send size={15} />{busy ? '보내는 중...' : '보내기'}</button></footer></DeliveryDialog>}
    {showSettings && <DeliveryDialog title="보내는 계정 설정" busy={busy} onClose={() => setShowSettings(false)}>
      <p className="email-setup-intro">{settings?.configured ? '이 계정으로 등록된 수신자에게 보고서를 보냅니다.' : '이메일을 보내려면 발신 계정이 하나 필요해요. 서비스 관리자가 서버에서 한 번 설정하면 됩니다.'}</p>
      <div className="delivery-provider"><span className="channel-recipient-avatar"><Mail size={22} /></span><div><strong>{settings?.configured ? provider : '아직 등록된 계정이 없어요'}</strong><p>{settings?.configured ? settings.senderAddress : 'Gmail 또는 네이버 메일을 사용할 수 있습니다.'}</p></div>{settings?.configured && <span className="delivery-state is-on">설정됨</span>}</div>
      <details className="email-setup-guide"><summary>관리자용 설정 방법<ChevronDown size={14} /></summary>
        <ol className="delivery-setup-steps"><li>사용할 Gmail 또는 네이버 계정의 앱 비밀번호를 준비하세요.</li><li>서버에 아래 환경변수를 설정하고 재시작하세요.</li><li>이 창에서 <b>설정 상태 다시 확인</b>을 누르세요.</li></ol>
        <dl><div><dt>APP_EMAIL_PROVIDER</dt><dd>GMAIL 또는 NAVER</dd></div><div><dt>APP_EMAIL_USERNAME</dt><dd>보내는 이메일 주소</dd></div><div><dt>APP_EMAIL_PASSWORD</dt><dd>앱 비밀번호</dd></div></dl>
        <div className="delivery-provider-links"><a href="https://support.google.com/accounts/answer/185833?hl=ko" target="_blank" rel="noopener noreferrer">Gmail 설정 안내<ArrowUpRight size={14} /></a><a href="https://help.naver.com/service/30029/contents/21351?osType=COMMONOS" target="_blank" rel="noopener noreferrer">네이버 설정 안내<ArrowUpRight size={14} /></a></div>
      </details>
      {settings?.configured && <p className="email-setup-footnote">실제 연결은 받는 사람 옆의 ‘테스트’로 확인할 수 있어요.</p>}
      {setupFeedback && <p className="email-setup-feedback" role="status">{setupFeedback}</p>}
      {actionError && <InlineError message={actionError} />}
      <footer className="email-setup-footer"><button type="button" className="button button--subtle" disabled={busy} onClick={() => setShowSettings(false)}>{settings?.configured ? '완료' : '나중에'}</button>
        <button type="button" className={'button ' + (settings?.configured && !settings.enabled ? 'button--secondary' : 'button--primary')} disabled={busy} onClick={refreshSender}><RefreshCw size={14} />{busy ? '처리 중...' : '설정 상태 다시 확인'}</button>
        {settings?.configured && !settings.enabled && <button type="button" className="button button--primary" disabled={busy} onClick={() => void run(async () => { await persist(recipients, true); setShowSettings(false); setToast('이메일 자동 발송을 켰어요.') })}>자동 발송 켜기</button>}
      </footer>
    </DeliveryDialog>}

  </div>
}
