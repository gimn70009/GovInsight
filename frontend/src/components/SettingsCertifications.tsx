import { useEffect, useId, useRef, useState } from 'react'
import { BadgeCheck, CalendarDays, Check, ChevronDown, ChevronUp, CircleAlert, CircleCheck, CircleHelp, Info, Pencil, Plus, Trash2 } from 'lucide-react'
import type { CertificationStatus, RegisteredCompany } from '../api/evaluationSettings'
import './SettingsRecordCards.css'

type Certification = RegisteredCompany['certifications'][number]
const statuses = {
  HELD: { label: '보유', icon: CircleCheck },
  NEEDS_VERIFICATION: { label: '확인 필요', icon: CircleHelp },
  EXPIRED: { label: '만료', icon: CircleAlert },
} as const

export default function SettingsCertifications({ certifications, onChange }: {
  certifications: Certification[]
  onChange: (certifications: Certification[]) => void
}) {
  const [editingIndex, setEditingIndex] = useState<number | null>(null)
  const editingToggle = useRef<HTMLButtonElement>(null)
  const nameInput = useRef<HTMLInputElement>(null)
  const addButton = useRef<HTMLButtonElement>(null)
  const sectionId = useId()

  useEffect(() => {
    if (editingIndex !== null) nameInput.current?.focus()
  }, [editingIndex])

  const update = (index: number, change: Partial<Certification>) => {
    onChange(certifications.map((certification, current) => current === index ? { ...certification, ...change } : certification))
  }
  const add = () => {
    if (certifications.length >= 20) return
    onChange([...certifications, { name: '', expiresOn: null, status: 'NEEDS_VERIFICATION' }])
    setEditingIndex(certifications.length)
  }
  const closeEditor = () => {
    editingToggle.current?.focus()
    setEditingIndex(null)
  }
  const remove = (index: number) => {
    onChange(certifications.filter((_, current) => current !== index))
    setEditingIndex(null)
    requestAnimationFrame(() => addButton.current?.focus())
  }
  const addControl = <button ref={addButton} type="button" className="settings-record-section__add" disabled={certifications.length >= 20} onClick={add}>
    <Plus size={16} />인증·자격 추가
  </button>

  return <section className="settings-surface settings-optional settings-record-section settings-certifications">
    <details>
      <summary>
        <span>
          <span className="settings-section-icon"><BadgeCheck size={19} /></span>
          <strong>인증·자격</strong>
          <b className="settings-record-section__count">{certifications.length}건</b>
        </span>
        <ChevronDown size={18} />
      </summary>
      <div className="settings-record-section__body">
        {certifications.length === 0 ? <div className="settings-record-section__empty">
          <span className="settings-record-section__empty-icon"><BadgeCheck size={25} aria-hidden="true" /></span>
          <div>
            <h3>등록된 인증·자격이 없어요</h3>
            <p>보유한 인증과 유효기간을 추가해 주세요.</p>
          </div>
          {addControl}
        </div> : <>
          <div className="settings-record-section__toolbar">
            <div className="settings-record-section__totals" aria-label="상태별 인증·자격 수">
              {(Object.keys(statuses) as CertificationStatus[]).map(status => {
                const count = certifications.filter(certification => certification.status === status).length
                return count > 0 && <span key={status} data-status={status}><i aria-hidden="true" />{statuses[status].label}<b>{count}</b></span>
              })}
            </div>
            {addControl}
          </div>
          <div className="settings-record-section__grid">
            {certifications.map((certification, index) => {
              const editing = editingIndex === index
              const StatusIcon = statuses[certification.status].icon
              const titleId = `${sectionId}-title-${index}`
              const editorId = `${sectionId}-editor-${index}`
              const name = certification.name.trim() || '새 인증·자격'
              return <article key={index} className={`settings-record-card${editing ? ' is-editing' : ''}`} data-status={certification.status} aria-labelledby={titleId}>
                <div className="settings-record-card__top">
                  <span className="settings-record-status"><StatusIcon size={14} aria-hidden="true" />{statuses[certification.status].label}</span>
                  <button ref={editing ? editingToggle : undefined} type="button" className="settings-record-card__edit" aria-label={`${name} ${editing ? '편집 접기' : '수정'}`}
                    aria-expanded={editing} aria-controls={editorId} onClick={() => editing ? closeEditor() : setEditingIndex(index)}>
                    {editing ? <ChevronUp size={15} /> : <Pencil size={14} />}{editing ? '접기' : '수정'}
                  </button>
                </div>
                <h3 id={titleId}>{name}</h3>
                {editing ? <div id={editorId} className="settings-record-card__editor">
                  <div className="settings-form-grid">
                    <label className="settings-form-wide">인증·자격명 <span className="settings-required">*</span>
                      <input ref={nameInput} value={certification.name} maxLength={150} placeholder="예: 벤처기업확인, ISO 9001"
                        onChange={event => update(index, { name: event.target.value })} />
                    </label>
                    <label>상태
                      <select value={certification.status} onChange={event => update(index, { status: event.target.value as CertificationStatus })}>
                        <option value="HELD">보유</option><option value="NEEDS_VERIFICATION">확인 필요</option><option value="EXPIRED">만료</option>
                      </select>
                    </label>
                    <label>유효기간
                      <input type="date" value={certification.expiresOn ?? ''} onChange={event => update(index, { expiresOn: event.target.value || null })} />
                    </label>
                  </div>
                  <div className="settings-record-card__footer">
                    <button type="button" className="settings-record-card__delete" onClick={() => remove(index)}><Trash2 size={15} />인증·자격 삭제</button>
                    <button type="button" className="settings-edit-button" onClick={closeEditor}><Check size={15} />입력 접기</button>
                  </div>
                </div> : <p className="settings-record-card__expiry">
                  <CalendarDays size={15} aria-hidden="true" />
                  {certification.expiresOn ? <>유효기간 <time dateTime={certification.expiresOn}>{certification.expiresOn.replaceAll('-', '.')}까지</time></> : '유효기간 미등록'}
                </p>}
              </article>
            })}
          </div>
        </>}
        <p className="settings-record-section__note"><Info size={14} aria-hidden="true" />등록한 정보는 사용자 입력이며, 공식 증빙은 별도로 확인해요.</p>
      </div>
    </details>
  </section>
}
