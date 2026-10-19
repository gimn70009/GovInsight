import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Search, X } from 'lucide-react'
import type { SelectionGroup } from '../api/evaluationSettings'
import { filterSettingsOptions } from '../utils/evaluationSettings'

export default function SettingsKeywordPicker({ title, groups, selected, limit, onClose, onApply }: {
  title: string; groups: SelectionGroup[]; selected: string[]; limit: number
  onClose: () => void; onApply: (values: string[]) => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const optionsList = useRef<HTMLDivElement>(null)
  const selectionToggle = useRef<HTMLButtonElement>(null)
  const id = useId()
  const [category, setCategory] = useState(groups[0]?.name ?? '')
  const [query, setQuery] = useState('')
  const [draft, setDraft] = useState(selected)
  const [showSelected, setShowSelected] = useState(false)
  const [error, setError] = useState('')
  const matches = useMemo(() => filterSettingsOptions(groups, category, query), [groups, category, query])
  const options = showSelected ? draft : matches
  const searching = query.trim().length > 0

  useEffect(() => {
    const element = dialog.current
    element?.showModal()
    return () => element?.close()
  }, [])

  const toggle = (value: string) => {
    setError('')
    if (draft.includes(value)) {
      setDraft(draft.filter(item => item !== value))
      if (showSelected) {
        const nextIndex = draft.indexOf(value)
        requestAnimationFrame(() => {
          const inputs = optionsList.current?.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')
          const next = inputs?.[Math.min(nextIndex, inputs.length - 1)] ?? selectionToggle.current
          next?.focus()
        })
      }
    }
    else if (draft.length >= limit) setError(`최대 ${limit}개까지 선택할 수 있습니다.`)
    else setDraft([...draft, value])
  }

  return <dialog ref={dialog} className="settings-picker" aria-labelledby={`${id}-title`} onCancel={event => { event.preventDefault(); onClose() }}>
    <header className="settings-picker__header">
      <div><h2 id={`${id}-title`}>{title}</h2>{limit === 3 && <p>최대 3개까지 선택하세요.</p>}</div>
      <button type="button" className="settings-icon-button" onClick={onClose} aria-label="선택창 닫기"><X size={20} /></button>
    </header>
    <div className="settings-picker__body">
      <label className="settings-search">
        <Search size={18} aria-hidden="true" />
        <input autoFocus value={query} onChange={event => { setQuery(event.target.value); setShowSelected(false) }} aria-label={`${title} 검색`} placeholder="등록된 항목에서 검색" />
        {query && <button type="button" onClick={() => setQuery('')} aria-label="검색어 지우기"><X size={16} /></button>}
      </label>
      {groups.length > 1 && <div className="settings-categories" role="group" aria-label="키워드 분야">
        {groups.map(group => <button key={group.name} type="button" aria-pressed={!showSelected && !searching && category === group.name} onClick={() => { setCategory(group.name); setQuery(''); setShowSelected(false) }}>{group.name}</button>)}
      </div>}
      {(searching || showSelected) && <p className="settings-picker__result-label" role="status">{showSelected ? '선택한 항목' : `전체 분야 검색 결과 ${options.length}개`}</p>}
      <div ref={optionsList} id={`${id}-options`} className="settings-options">
        {options.map(option => <label key={option} className={`settings-option${draft.includes(option) ? ' is-selected' : ''}`}>
          <input type="checkbox" checked={draft.includes(option)} onChange={() => toggle(option)} /><span>{option}</span>
        </label>)}
        {!options.length && <p className="settings-picker__empty">{showSelected ? '선택한 항목이 없습니다.' : '일치하는 등록 항목이 없습니다.'}</p>}
      </div>
      {error && <p className="settings-field-error" role="alert">{error}</p>}
    </div>
    <footer className="settings-picker__footer">
      <button ref={selectionToggle} type="button" className="settings-picker__selection-toggle" aria-label={`선택한 항목 ${draft.length}개만 보기`} aria-pressed={showSelected} aria-controls={`${id}-options`} onClick={() => { setShowSelected(value => !value); setQuery('') }}>
        <span>선택한 항목</span><strong className="settings-picker__selection-count">{draft.length}</strong>
      </button>
      <div><button type="button" className="button button--secondary" onClick={onClose}>취소</button><button type="button" className="button button--primary" disabled={!draft.length} onClick={() => onApply(draft)}>선택 완료</button></div>
    </footer>
  </dialog>
}
