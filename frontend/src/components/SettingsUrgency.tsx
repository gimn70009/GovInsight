import { useId } from 'react'
import { ChevronDown, Lightbulb } from 'lucide-react'
import './SettingsUrgency.css'

const deadlineOptions = [
  { days: 3, title: '3일 전', description: '마감이 임박한 공고부터' },
  { days: 7, title: '7일 전', description: '1주일 앞서 확인' },
  { days: 14, title: '14일 전', description: '2주일 앞서 확인' },
  { days: 30, title: '30일 전', description: '약 1개월 앞서 확인' },
]

export default function SettingsUrgency({ days, onChange }: { days: number; onChange: (days: number) => void }) {
  const id = useId()
  const bands = [
    { label: '우선 확인', range: `0~${days}일`, score: 100 },
    { label: '준비 검토', range: `${days + 1}~${days * 2}일`, score: 65 },
    { label: '일정 확인', range: `${days * 2 + 1}~${days * 4}일`, score: 35 },
    { label: '추후 검토', range: `${days * 4 + 1}일 이상`, score: 10 },
  ]

  return <div className="settings-criterion-layout">
    <aside className="settings-urgency-guide" aria-labelledby={`${id}-guide-title`}>
      <div className="settings-urgency-guide__heading">
        <Lightbulb size={19} aria-hidden="true" />
        <div><h4 id={`${id}-guide-title`}>마감이 가까울수록 긴급도가 높아요</h4><p>공고를 분석한 날부터 신청 마감일까지 남은 기간을 봐요.</p></div>
      </div>
      <ol className="settings-urgency-bands" aria-label="신청 마감까지 남은 기간별 긴급도">
        {bands.map(band => <li className="settings-urgency-band" key={band.score}>
          <span className="settings-urgency-band__label">{band.label}</span>
          <strong className="settings-urgency-band__range">{band.range} 남음</strong>
          <span className="settings-urgency-band__score">긴급도 <b>{band.score}</b>점</span>
        </li>)}
      </ol>
      <details className="settings-urgency-details">
        <summary><span>구간이 나뉘는 방식과 예외 기준</span><ChevronDown size={16} aria-hidden="true" /></summary>
        <div className="settings-urgency-details__content">
          <p>선택한 <strong>{days}일</strong>을 첫 기준으로, 2배인 <strong>{days * 2}일</strong>과 4배인 <strong>{days * 4}일</strong>에서 구간을 나눠요. 0일은 마감 당일이에요.</p>
          <dl>
            <div><dt>마감일을 확인할 수 없음</dt><dd>10점 · 마감일 확인 필요</dd></div>
            <div><dt>선착순·예산 소진 시 종료하는 상시 접수</dt><dd>70점 · 조기 종료에 대비</dd></div>
            <div><dt>접수 종료 또는 회사가 대응할 일이 없음</dt><dd>0점</dd></div>
          </dl>
          <p>신청 준비 시간이 충분한지는 ‘실행 가능성’에서 따로 확인해요. 최종 기회점수에는 회사 적합도 등 다른 기준도 함께 반영돼요.</p>
        </div>
      </details>
    </aside>
    <fieldset className="settings-urgency-choice" aria-describedby={`${id}-choice-help`}>
      <legend>마감 며칠 전부터 우선 확인할까요?</legend>
      <p id={`${id}-choice-help`}>선택한 기간 안에 마감하는 공고에 긴급도 100점을 부여해요.</p>
      <div className="settings-urgency-options">
        {deadlineOptions.map(option => <label className={`settings-urgency-option${days === option.days ? ' is-selected' : ''}`} key={option.days}>
          <span><strong>{option.title}</strong><small>{option.description}</small></span>
          <input type="radio" name={`${id}-days`} value={option.days} checked={days === option.days} onChange={() => onChange(option.days)} />
        </label>)}
      </div>
      <p className="settings-urgency-choice__note">기간을 바꾸면 위의 긴급도 구간도 함께 바뀌어요.</p>
    </fieldset>
  </div>
}
