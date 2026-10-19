import { useId } from 'react'
import { CalendarDays, UsersRound } from 'lucide-react'
import type { EvaluationCriteria } from '../api/evaluationSettings'
import { parsePreparationDays } from '../utils/evaluationSettings'
import './SettingsFeasibilityOptions.css'

interface Props {
  days: string
  mode: EvaluationCriteria['participationMode']
  onDaysChange: (days: string) => void
  onModeChange: (mode: EvaluationCriteria['participationMode']) => void
}

export default function SettingsFeasibilityOptions({ days, mode, onDaysChange, onModeChange }: Props) {
  const id = useId()
  const parsedDays = parsePreparationDays(days)
  const invalidDays = parsedDays !== null && (!Number.isInteger(parsedDays) || parsedDays < 1 || parsedDays > 365)

  return <div className="settings-feasibility-options">
    <div className="settings-feasibility-field">
      <div className="settings-feasibility-field__heading"><span className="settings-feasibility-field__icon"><CalendarDays size={18} aria-hidden="true" /></span><label htmlFor={`${id}-days`}>최소 신청 준비 기간</label></div>
      <p id={`${id}-days-help`}>서류·제안서 준비에 필요한 시간이에요.<br />마감까지 이 기간이 남아 있는지 확인해요.</p>
      <div className="settings-feasibility-field__control">
      <div className={`settings-preparation-input${invalidDays ? ' is-invalid' : ''}`}>
        <input id={`${id}-days`} type="text" inputMode="numeric" pattern="[0-9]*" value={days}
          placeholder="예: 10" aria-invalid={invalidDays} aria-describedby={`${id}-days-help ${id}-days-hint`}
          onChange={event => onDaysChange(event.target.value)} />
        <span aria-hidden="true">일</span>
      </div>
      <small id={`${id}-days-hint`} className={invalidDays ? 'is-invalid' : undefined}>
        {invalidDays ? '1~365일 사이의 정수로 입력해 주세요.' : '1~365일 · 비워두면 미정'}
      </small>
      </div>
    </div>
    <div className="settings-feasibility-field">
      <div className="settings-feasibility-field__heading"><span className="settings-feasibility-field__icon"><UsersRound size={18} aria-hidden="true" /></span><label htmlFor={`${id}-mode`}>참여 방식 선호</label></div>
      <p id={`${id}-mode-help`}>우리 회사 단독 또는 다른 기업·기관과 공동으로<br className="settings-participation-break" /> 신청할지 정하는 선호 조건이에요.</p>
      <div className="settings-feasibility-field__control">
      <select id={`${id}-mode`} value={mode} aria-describedby={`${id}-mode-help`}
        onChange={event => onModeChange(event.target.value as EvaluationCriteria['participationMode'])}>
        <option value="ANY">단독·공동 모두 검토</option>
        <option value="SOLO">단독 참여 선호</option>
        <option value="CONSORTIUM">공동 참여 선호</option>
      </select>
      </div>
    </div>
  </div>
}
