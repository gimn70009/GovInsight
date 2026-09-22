import { useId, type ReactNode } from 'react'
import { Clock3, Pencil, Plus, Send, Settings2, Trash2 } from 'lucide-react'
import './DeliveryChannelPanel.css'

export function ChannelAutomation({ channel, enabled, busy, setupHint, onToggle, onSettings }: {
  channel: string; enabled: boolean; busy: boolean; setupHint?: string; onToggle: () => void; onSettings: () => void
}) {
  const descriptionId = useId()
  return <section className="channel-automation" aria-label={channel + ' 자동 발송 설정'}>
    <div className="channel-automation-copy"><span className="channel-automation-icon" aria-hidden="true"><Clock3 size={20} /></span><div>
      <h3>자동 발송</h3><p id={descriptionId}>{setupHint || (enabled ? '모니터링이 끝나면 선택한 사람에게 보고서를 보냅니다.' : '자동 발송이 꺼져 있어요. 켜면 선택한 사람에게 보고서를 보냅니다.')}</p>
    </div></div>
    <div className="channel-automation-actions"><button type="button" className="channel-settings-button" disabled={busy} onClick={onSettings}><Settings2 size={15} />발송 설정</button>
      <button type="button" role="switch" aria-label={channel + ' 보고서 발송'} aria-describedby={descriptionId} aria-checked={enabled}
        className={'schedule-switch ' + (enabled ? 'schedule-switch--on' : '')} disabled={busy} onClick={onToggle}>
        <span>{enabled ? '켜짐' : '꺼짐'}</span><i aria-hidden="true" /></button>
    </div>
  </section>
}

export function ChannelRecipients({ count, selected, busy, onAdd, children }: {
  count: number; selected: number; busy: boolean; onAdd: () => void; children: ReactNode
}) {
  return <section className="channel-recipients" aria-label="받는 사람 관리">
    <header className="channel-recipient-header"><div><h2>받는 사람 <span className="channel-count">{count}</span></h2>
      <p>체크한 사람에게만 보냅니다.<span>{selected}명 선택</span></p></div>
      <button type="button" className="button button--primary" disabled={busy || count >= 20} onClick={onAdd}><Plus size={16} />수신자 추가</button>
    </header>
    <div className="channel-recipient-list">{children}</div>
  </section>
}

export function ChannelRecipient({ name, address, enabled, busy, icon, feedback, extraAction, onToggle, onTest, onEdit, onDelete }: {
  name: string; address: string; enabled: boolean; busy: boolean; icon: ReactNode; feedback?: ReactNode; extraAction?: ReactNode;
  onToggle: () => void; onTest: () => void; onEdit: () => void; onDelete: () => void
}) {
  const label = name || address
  return <div className={'channel-recipient-row ' + (enabled ? 'is-selected' : 'is-unselected')}>
    <label className="channel-recipient-choice"><input type="checkbox" aria-label={label + ' 수신'} checked={enabled} disabled={busy} onChange={onToggle} />
      <span className="channel-recipient-avatar" aria-hidden="true">{icon}</span>
      <span className="channel-recipient-person"><strong>{label}</strong><small>{address}</small>{feedback}</span>
    </label>
    <div className="channel-recipient-actions">{extraAction}
      <button type="button" className="channel-test-button" aria-label={label + ' 테스트 발송'} disabled={busy} onClick={onTest}><Send size={14} />테스트</button>
      <button type="button" className="icon-button" aria-label={label + ' 수정'} title="수정" disabled={busy} onClick={onEdit}><Pencil size={16} /></button>
      <button type="button" className="icon-button" aria-label={label + ' 삭제'} title="삭제" disabled={busy} onClick={onDelete}><Trash2 size={16} /></button>
    </div>
  </div>
}
