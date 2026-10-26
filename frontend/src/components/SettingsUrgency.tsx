import { useId } from 'react'
import { ChevronDown, Lightbulb } from 'lucide-react'
import { parseUrgencyDays } from '../utils/evaluationSettings'
import './SettingsUrgency.css'

export default function SettingsUrgency({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const id = useId()
  const days = parseUrgencyDays(value)
  const valid = Number.isInteger(days) && days >= 1 && days <= 365
  const bands = valid ? [
    { label: '우선 확인', range: `0~${days}일`, score: 100 },
    { label: '준비 검토', range: `${days + 1}~${days * 2}일`, score: 65 },
    { label: '일정 확인', range: `${days * 2 + 1}~${days * 4}일`, score: 35 },
    { label: '추후 검토', range: `${days * 4 + 1}일 이상`, score: 10 },
  ] : []

  return <div className="settings-criterion-layout">
    <aside className="settings-urgency-guide" aria-labelledby={`${id}-guide-title`}>
      <div className="settings-urgency-guide__heading">
        <Lightbulb size={19} aria-hidden="true" />
        <div><h4 id={`${id}-guide-title`}>마감이 가까울수록 긴급도가 높아요</h4><p>공고를 분석한 날부터 신청 마감일까지 남은 기간을 봐요.</p></div>
      </div>
      {valid ? <ol className="settings-urgency-bands" aria-label="신청 마감까지 남은 기간별 긴급도">
        {bands.map(band => <li className="settings-urgency-band" key={band.score}>
          <span className="settings-urgency-band__label">{band.label}</span>
          <strong className="settings-urgency-band__range">{band.range} 남음</strong>
          <span className="settings-urgency-band__score">긴급도 <b>{band.score}</b>점</span>
        </li>)}
      </ol> : <p className="settings-urgency-empty" role="status">기간을 입력하면 점수 구간이 표시돼요.</p>}
      <details className="settings-urgency-details">
        <summary><span>구간이 나뉘는 방식과 예외 기준</span><ChevronDown size={16} aria-hidden="true" /></summary>
        <div className="settings-urgency-details__content">
          <p>{valid ? <>입력한 <strong>{days}일</strong>을 첫 기준으로, 2배인 <strong>{days * 2}일</strong>과 4배인 <strong>{days * 4}일</strong>에서 구간을 나눠요.</> : '입력한 기간을 기준으로 2배와 4배에서 구간을 나눠요.'} 0일은 마감 당일이에요.</p>
          <dl>
            <div><dt>마감일을 확인할 수 없음</dt><dd>10점 · 마감일 확인 필요</dd></div>
            <div><dt>선착순·예산 소진 시 종료하는 상시 접수</dt><dd>70점 · 조기 종료에 대비</dd></div>
            <div><dt>접수 종료 또는 회사가 대응할 일이 없음</dt><dd>0점</dd></div>
          </dl>
          <p>신청 준비 시간이 충분한지는 ‘실행 가능성’에서 따로 확인해요. 최종 기회점수에는 회사 적합도 등 다른 기준도 함께 반영돼요.</p>
        </div>
      </details>
    </aside>
    <div className="settings-urgency-choice">
      <div className="settings-urgency-choice__copy">
        <label htmlFor={`${id}-days`}>마감 며칠 전부터 우선 확인할까요?</label>
        <p id={`${id}-choice-help`}>입력한 기간 안에 마감하는 공고에 긴급도 100점을 부여해요.<br />기간을 바꾸면 위의 점수 구간도 함께 바뀌어요.</p>
      </div>
      <div className="settings-urgency-choice__control">
        <div className={`settings-urgency-input${valid ? '' : ' is-invalid'}`}>
          <span aria-hidden="true">마감</span>
          <input id={`${id}-days`} type="text" inputMode="numeric" pattern="[0-9]*" value={value}
            placeholder="예: 10" aria-required="true" aria-invalid={!valid} aria-describedby={`${id}-choice-help ${id}-days-hint`}
            onChange={event => onChange(event.target.value)} />
          <span aria-hidden="true">일 전부터</span>
        </div>
        <small id={`${id}-days-hint`} className={valid ? undefined : 'is-invalid'}>
          {valid ? '1~365일 · 원하는 기간을 직접 입력하세요.' : '1~365일 사이의 정수로 입력해 주세요.'}
        </small>
      </div>
    </div>
  </div>
}
