import { useId, useState } from 'react'
import { ChevronDown, ClipboardCheck, ExternalLink } from 'lucide-react'
import type { DocumentDetail, ParticipationRule, ParticipationCriterionEntry } from '../api/types'
import { evidenceAttachment } from '../utils/businessSummary'
import { participationStatement } from '../utils/participationStatement'
import { categoryTables } from '../utils/participationCategoryTables'
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

function Rule({ entry, detail }: { entry: ParticipationCriterionEntry; detail: DocumentDetail }) {
  const item = entry.rule
  const [open, setOpen] = useState(false)
  const id = useId()
  const pending = detail.participationGuide?.rewriteStatus === 'PENDING'
  const verified = entry.displayStatus === 'VERIFIED'
  return <li className="participation-guide__rule">
    <div className="participation-guide__rule-heading">
      {verified && <div className="participation-guide__description">
        <div className="participation-guide__badges">
          {item.section === 'EXCLUSION' && <span className="is-exclusion">해당 시 제외</span>}
          {item.section === 'PREFERENCE' && <span className="is-preference">우대·가점</span>}
          {item.section === 'DEDUCTION' && <span className="is-exclusion">감점</span>}
        </div>
        <div className="participation-guide__main"><p>{participationStatement(entry)}</p></div>
      </div>}
      {!verified && pending && <p className="participation-guide__pending-row">조건을 정리하고 있습니다.</p>}
      <div className="participation-guide__actions">
        <button type="button" className="business-summary__organization-toggle"
          aria-label={item.label + ' 근거 보기'} aria-expanded={open} aria-controls={id}
          onClick={() => setOpen(value => !value)}>
          근거 보기<ChevronDown size={13} aria-hidden="true" />
        </button>
      </div>
    </div>
    <div id={id} hidden={!open} className="participation-guide__sources">
      {open && <RuleEvidence item={item} detail={detail} />}
    </div>
  </li>
}

export function ParticipationConditions({ detail }: { detail: DocumentDetail }) {
  const categories = categoryTables(detail.participationGuide)
  const headingId = useId()
  const pending = detail.participationGuide?.rewriteStatus === 'PENDING'
  const unavailable = detail.participationGuide?.rewriteStatus === 'UNAVAILABLE'
  const verifiedCount = categories.flatMap(category => category.rows).flatMap(row => row.entries)
    .filter(entry => entry.displayStatus === 'VERIFIED').length
  return <section className="business-summary__card participation-guide" aria-labelledby={headingId}>
    <header className="business-summary__heading">
      <span className="business-summary__heading-icon" aria-hidden="true"><ClipboardCheck size={22} /></span>
      <div className="business-summary__heading-text"><h3 id={headingId}>신청 자격과 제한</h3>
        <p>공고의 참여 조건을 8개 분류와 세부 항목별로 확인하세요.</p>
      </div>
    </header>
    <div className="business-summary__section-body">
      <p className="participation-guide__intro">공고에 명시된 참여 조건을 항목별로 확인하세요. 각 조건의 원문은 근거 보기에서 확인할 수 있습니다.</p>
      {pending && <div role="status" className="participation-guide__progress">
        <strong>{verifiedCount > 0 ? `${verifiedCount}개 조건을 정리했습니다. 나머지 조건을 확인하고 있습니다.` : '공고의 신청 자격과 제한을 정리하고 있습니다.'}</strong>
        <p>처음 정리하는 공고는 몇 분이 걸릴 수 있습니다. 확인이 끝난 조건부터 자동으로 표시됩니다.</p>
      </div>}
      {unavailable && <p className="participation-guide__progress">조건 정리를 완료하지 못했습니다. 지금은 근거 보기에서 원문을 확인할 수 있습니다. 잠시 후 공고를 다시 열면 재시도합니다.</p>}
      {categories.map((category, index) => <section key={category.code}
        className={'participation-guide__section is-' + category.code.toLowerCase()} aria-labelledby={headingId + category.code}>
        <div className="participation-guide__section-title"><h4 id={headingId + category.code}>{index + 1}. {category.label}</h4></div>
        <div className="participation-guide__table-wrap">
          <table className="participation-guide__table" aria-label={category.label}>
            <colgroup><col className="participation-guide__category-col" /><col /></colgroup>
            <thead><tr><th scope="col">세부 항목</th><th scope="col">공고에 명시된 조건</th></tr></thead>
            <tbody>{category.rows.map(row => <tr key={row.code} data-criterion={row.code}>
              <th scope="row">{row.label}</th>
              <td>{row.entries.length > 0
                ? <ul className="participation-guide__rules">{row.entries.map((entry, entryIndex) =>
                    <Rule key={detail.detectionId + ':' + entry.rule.text + entryIndex} entry={entry} detail={detail} />)}</ul>
                : <span className={'participation-guide__missing' + (row.status === 'NOT_STATED' ? '' : ' is-unread')}>
                    {pending ? '조건을 확인하고 있습니다.' : row.status === 'NOT_STATED' ? '공고에 명시 없음' : '—'}
                  </span>}
              </td>
            </tr>)}</tbody>
          </table>
        </div>
      </section>)}
      <p className="business-summary__footnote">‘명시 없음’은 조건이 없다는 판정이 아니라 확인한 공고에서 해당 기준을 찾지 못했다는 뜻입니다. 확인하지 못한 항목은 —로 표시합니다.</p>
    </div>
  </section>
}
