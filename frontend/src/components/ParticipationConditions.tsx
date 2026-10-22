import { useId, useState } from 'react'
import { ChevronDown, ClipboardCheck, ExternalLink } from 'lucide-react'
import type { DocumentDetail, ParticipationRule, ParticipationCriterionEntry } from '../api/types'
import { evidenceAttachment } from '../utils/businessSummary'
import { companyParticipationRows, companyConditionEmptyText, companyConditionScope, formatCompanyCondition, companyConditionSentence } from '../utils/companyParticipation'
import './ParticipationConditions.css'

type RuleSource = ParticipationRule['sources'][number]

function sourceKey(source: RuleSource) {
  return JSON.stringify([source.origin, source.attachmentName, source.sectionTitle, source.location, source.excerpt])
}

function sourceName(source: RuleSource) {
  return source.origin === 'NOTICE_BODY' ? '공고 본문' : source.attachmentName || '첨부 문서'
}

function RuleEvidence({ item, detail }: { item: ParticipationRule; detail: DocumentDetail }) {
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const id = useId()
  // Keep distinct originals, including conflicting text; only remove exact duplicate citations.
  const sources = [...new Map(item.sources.filter(source => source.excerpt?.trim())
    .map(source => [sourceKey(source), source])).values()]
  const selectedIndex = Math.max(0, sources.findIndex(source => sourceKey(source) === selectedKey))
  const source = sources[selectedIndex]
  if (!source) return null
  const file = evidenceAttachment({ source }, detail.attachments)
  return <div className="business-summary__document-source participation-guide__source">
    <strong>{item.label} · 원문 근거</strong>
    {sources.length > 1 ? <div className="participation-guide__source-selector">
      <label htmlFor={id}>원문 선택</label>
      <select id={id} value={selectedIndex} title={sourceName(source)}
        onChange={event => setSelectedKey(sourceKey(sources[Number(event.target.value)]))}>
        {sources.map((value, index) => <option key={sourceKey(value)} value={index}>
          {index + 1}. {[sourceName(value), value.sectionTitle, value.location].filter(Boolean).join(' · ')}
        </option>)}
      </select>
    </div> : <span>{sourceName(source)}</span>}
    <small>{[source.sectionTitle, source.location].filter(Boolean).join(' · ')}</small>
    <blockquote key={sourceKey(source)} tabIndex={0} aria-label="선택한 원문 근거">{source.excerpt}</blockquote>
    {file && <a href={file.downloadUrl} target="_blank" rel="noreferrer">근거 문서 열기<ExternalLink size={12} aria-hidden="true" /></a>}
  </div>
}

function Rule({ entry, detail, label, code }: {
  entry: ParticipationCriterionEntry; detail: DocumentDetail; label: string; code: string
}) {
  const item = entry.rule
  const [open, setOpen] = useState(false)
  const id = useId()
  const scope = companyConditionScope(entry)
  const condition = companyConditionSentence(item.text, item.section === 'PREFERENCE', code)
  const scopeText = scope ? formatCompanyCondition(scope) : null
  const hasEvidence = item.sources.some(source => source.excerpt?.trim())
  return <li className="participation-guide__rule">
    <div className="participation-guide__description">
      {scopeText && <p className="participation-guide__scope">
        <span className="participation-guide__scope-label">적용 유형</span>
        <strong>{[scopeText.lead, ...scopeText.notes].join(' ')}</strong>
      </p>}
      {item.section === 'EXCLUSION' && <span className="participation-guide__exclusion">해당 시 신청 제외</span>}
      <p className="participation-guide__main">{condition}</p>
    </div>
    {hasEvidence && <button type="button" className="business-summary__organization-toggle participation-guide__evidence-button"
      aria-label={label + ' 근거 보기'} aria-expanded={open} aria-controls={id}
      onClick={() => setOpen(value => !value)}>
      근거 보기<ChevronDown size={13} aria-hidden="true" />
    </button>}
    <div id={id} hidden={!open} className="participation-guide__sources">
      {open && <RuleEvidence item={item} detail={detail} />}
    </div>
  </li>
}

export function ParticipationConditions({ detail }: { detail: DocumentDetail }) {
  const rows = companyParticipationRows(detail.participationGuide)
  const visibleRows = [...rows.filter(row => row.code === 'BUSINESS_FORM'), ...rows.filter(row => row.code !== 'BUSINESS_FORM')]
    .filter(row => row.mandatory.length || row.preference.length)
  const unresolved = rows.some(row => row.evidence.length)
  const headingId = useId()
  const status = detail.participationGuide?.rewriteStatus
  const pending = status === 'PENDING'
  const unavailable = status === 'UNAVAILABLE'
  return <section className="business-summary__card participation-guide" aria-labelledby={headingId}>
    <header className="business-summary__heading">
      <span className="business-summary__heading-icon" aria-hidden="true"><ClipboardCheck size={22} /></span>
      <div className="business-summary__heading-text"><h3 id={headingId}>신청 자격</h3>
        <p>신청 주체와 업력·매출·수출·인증·업종·실적·신용 요건을 확인하세요.</p>
      </div>
    </header>
    <div className="business-summary__section-body">
      {pending && <div role="status" className="participation-guide__progress">
        <strong>공고의 신청 자격과 우대사항을 확인하고 있습니다.</strong>
        <p>확인이 끝나면 이 표에 자동으로 표시됩니다.</p>
      </div>}
      {unavailable && <p className="participation-guide__progress">참여기업 조건 확인을 완료하지 못했습니다. 확인되지 않은 항목은 공고 원문을 참고해 주세요.</p>}
      {visibleRows.length > 0 ? <div className="participation-guide__table-wrap">
        <table className="participation-guide__table" aria-label="참여기업 필수 조건과 우대사항">
          <colgroup><col className="participation-guide__category-col" /><col /><col /></colgroup>
          <thead><tr><th scope="col">분야</th><th scope="col">필수 조건</th><th scope="col">우대사항</th></tr></thead>
          <tbody>{visibleRows.map(row => <tr key={row.code} data-criterion={row.code}>
            <th scope="row">
              <span>{row.label}</span>
            </th>
            {(['mandatory', 'preference'] as const).map(kind => <td key={kind} data-label={kind === 'mandatory' ? '필수 조건' : '우대사항'}>
              <span className="participation-guide__mobile-label" aria-hidden="true">{kind === 'mandatory' ? '필수 조건' : '우대사항'}</span>
              {row[kind].length > 0
                ? <ul className="participation-guide__rules">{row[kind].map((entry, index) =>
                    <Rule key={detail.detectionId + ':' + entry.rule.text + index} entry={entry} detail={detail} code={row.code}
                      label={row.label + (kind === 'mandatory' ? ' 필수 조건' : ' 우대사항')} />)}</ul>
                : <span className="participation-guide__missing">{companyConditionEmptyText(status, row.evidence.length > 0)}</span>}
            </td>)}
          </tr>)}</tbody>
        </table>
      </div> : !pending && <p className="participation-guide__missing">표시할 신청 조건을 확인하지 못했습니다. 공고 원문을 확인해 주세요.</p>}
      {!pending && unresolved && visibleRows.length > 0 && <p className="participation-guide__missing">일부 조건은 확인하지 못했습니다. 공고 원문도 함께 확인해 주세요.</p>}
      <p className="business-summary__footnote">본문 조건을 표시합니다. 세부 예외와 각주는 각 조건의 근거 보기에서 확인할 수 있습니다. ‘확인된 조건 없음’은 해당 조건이 없다는 뜻이 아니며, 이 표만으로 신청 가능 여부를 판단할 수 없습니다.</p>
    </div>
  </section>
}
