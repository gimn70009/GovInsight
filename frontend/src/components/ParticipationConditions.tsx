import { useId, useState } from 'react'
import { ChevronDown, ClipboardCheck, ExternalLink } from 'lucide-react'
import type { DocumentDetail, ParticipationRule } from '../api/types'
import { evidenceAttachment } from '../utils/businessSummary'
import { applicationTypeSentence, groupParticipationRules } from '../utils/participationGuide'
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

function Rule({ item, detail }: { item: ParticipationRule; detail: DocumentDetail }) {
  const [openPanel, setOpenPanel] = useState<'details' | 'evidence' | null>(null)
  const id = useId()
  const hasDetails = item.details.length + item.exceptions.length + item.guidance.length > 0
  const toggle = (panel: 'details' | 'evidence') => setOpenPanel(current => current === panel ? null : panel)
  const typeSentence = applicationTypeSentence(item, detail.participationGuide?.variants)
  const displayTitle = typeSentence ?? item.text
  const criteria = item.details.filter(text => !text.startsWith('법적 자격 기준: '))
  const legalCriteria = item.details.filter(text => text.startsWith('법적 자격 기준: ')).map(text => text.slice('법적 자격 기준: '.length))
  const detailsLabel = item.exceptions.length > 0 ? '세부 조건·예외' : '세부 조건'
  const criteriaLabel = ['PREFERENCE', 'DEDUCTION'].includes(item.section) ? '세부 기준' : '세부 자격·추가 요건'
  return <li className="participation-guide__rule">
    <div className="participation-guide__rule-heading">
      <div className="participation-guide__description">
        {item.audience && !typeSentence && <p className="participation-guide__audience"><span>적용 대상</span>{item.audience}</p>}
        <RuleSummary text={displayTitle} />
      </div>
      <div className="participation-guide__actions">
        {hasDetails && <button type="button"
          className={'business-summary__organization-toggle participation-guide__details-toggle' + (item.exceptions.length > 0 ? ' has-exceptions' : '')}
          aria-label={item.label + ' ' + detailsLabel} aria-expanded={openPanel === 'details'} aria-controls={id + '-details'}
          onClick={() => toggle('details')}>
          {detailsLabel}<ChevronDown size={13} aria-hidden="true" />
        </button>}
        <button type="button" className="business-summary__organization-toggle"
          aria-label={(item.audience || displayTitle) + ' 근거 보기'} aria-expanded={openPanel === 'evidence'} aria-controls={id + '-evidence'}
          onClick={() => toggle('evidence')}>
          근거 보기<ChevronDown size={13} aria-hidden="true" />
        </button>
      </div>
    </div>
    {hasDetails && <div id={id + '-details'} hidden={openPanel !== 'details'} className="participation-guide__detail-panel">
      {criteria.length > 0 && <div className="participation-guide__detail-section">
        <strong>{criteriaLabel}</strong>
        <ul className="participation-guide__clauses" aria-label={criteriaLabel}>{criteria.map((text, index) => <li key={index}><DetailClause text={text} /></li>)}</ul>
      </div>}
      {legalCriteria.length > 0 && <div className="participation-guide__detail-section">
        <strong>적용 법령</strong>
        <ul className="participation-guide__clauses" aria-label="적용 법령">{legalCriteria.map((text, index) => <li key={index}>{text}</li>)}</ul>
      </div>}
      {item.exceptions.length > 0 && <div className="participation-guide__detail-section is-exception">
        <strong>예외·유예 조건</strong>
        <ul className="participation-guide__clauses" aria-label="예외·유예 조건">{item.exceptions.map((text, index) => <li key={index}>{text}</li>)}</ul>
      </div>}
      {item.guidance.length > 0 && <div className="participation-guide__detail-section">
        <strong>확인 방법</strong>
        <ul className="participation-guide__clauses" aria-label="확인 방법">{item.guidance.map((text, index) => <li key={index}>{text}</li>)}</ul>
      </div>}
    </div>}
    <div id={id + '-evidence'} hidden={openPanel !== 'evidence'} className="participation-guide__sources">
      {openPanel === 'evidence' && <RuleEvidence item={item} detail={detail} />}
    </div>
  </li>
}

function RuleSummary({ text }: { text: string }) {
  const blocks: { list: boolean; lines: string[] }[] = []
  for (const line of text.split(/\n+/u).filter(value => value.trim())) {
    const list = line.startsWith('• ')
    const last = blocks.at(-1)
    if (list && last?.list) last.lines.push(line.slice(2))
    else blocks.push({ list, lines: [list ? line.slice(2) : line] })
  }
  return <div className="participation-guide__main">{blocks.map((block, index) => block.list
    ? <ul className="participation-guide__summary-options" key={index}>{block.lines.map((line, i) => <li key={i}>{line}</li>)}</ul>
    : <p key={index}>{block.lines[0]}</p>)}</div>
}

function DetailClause({ text }: { text: string }) {
  const lines = text.split(/\n+/u).filter(Boolean)
  if (lines.length === 1) return <>{text}</>
  return <div className="participation-guide__detail-clause"><strong>{lines[0]}</strong>
    {lines.slice(1).map((line, index) => <p key={index}>{line}</p>)}</div>
}

export function ParticipationConditions({ detail }: { detail: DocumentDetail }) {
  const sections = groupParticipationRules(detail.participationGuide, 'eligibility')
    .filter(section => section.key !== 'REVIEW')
  const variants = detail.participationGuide?.variants ?? []
  const headingId = useId()
  return <section className="business-summary__card participation-guide" aria-labelledby={headingId}>
    <header className="business-summary__heading">
      <span className="business-summary__heading-icon" aria-hidden="true"><ClipboardCheck size={22} /></span>
      <div className="business-summary__heading-text"><h3 id={headingId}>신청 자격과 제한</h3>
        <p>신청할 수 있는 기업·기관의 요건과 신청 제외 조건을 확인하세요.</p>
      </div>
    </header>
    <div className="business-summary__section-body">
      {sections.length > 0 ? <>
        {variants.length > 0 && <div className="participation-guide__variants" aria-label="지원 유형의 의미">
          {variants.map(variant => <span key={variant.name}><strong>{variant.name}</strong>{variant.description}</span>)}
        </div>}
        {sections.map(section => <section key={section.key} className={'participation-guide__section is-' + section.key.toLowerCase()}
          aria-labelledby={headingId + section.key}>
          <div className="participation-guide__section-title"><h4 id={headingId + section.key}>{section.title}</h4><span>{section.note}</span></div>
          {section.groups.map((group, index) => <div className="participation-guide__scope-group" key={group.scope ?? index}>
            <div className="participation-guide__table-wrap">
              <table className="participation-guide__table" aria-label={section.title + (group.scope ? ' · ' + group.scope : '')}>
                <colgroup><col className="participation-guide__category-col" /><col /></colgroup>
                <tbody>{group.labels.map(group => <tr key={group.label}>
                  <th scope="row">{group.label}</th>
                  <td><ul className="participation-guide__rules">{group.rules.map((item, index) =>
                    <Rule key={item.text + index} item={item} detail={detail} />)}</ul></td>
                </tr>)}</tbody>
              </table>
            </div>
          </div>)}
        </section>)}
        <p className="business-summary__footnote">공고에서 확인된 기준을 정리한 안내입니다. 우리 회사의 신청 가능 여부를 판정한 결과는 아닙니다.</p>
      </> : <div className="participation-guide__empty" role="status">
        <ClipboardCheck size={21} aria-hidden="true" />
        <div><strong>신청 자격 확인 필요</strong>
          <p>저장된 공고와 첨부에서 신청 자격을 확인하지 못했습니다. 조건이 없다는 뜻은 아니므로 공고 원문을 확인해 주세요.</p>
        </div>
      </div>}
    </div>
  </section>
}
