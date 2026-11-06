import { useId, useState } from 'react'
import { ChevronDown, ClipboardCheck, ExternalLink, FileText, ListChecks } from 'lucide-react'
import type { DocumentDetail, ParticipationRule, ParticipationCriterionEntry } from '../api/types'
import { evidenceAttachment } from '../utils/businessSummary'
import { companyParticipationRows, companyHasBusinessDivision, companyConditionSourceKey, companyConditionPresentation, companyConditionScope, companyConditionAppliesTo, activeCompanyConditionProgram } from '../utils/companyParticipation'
import './ParticipationConditions.css'

type RuleSource = ParticipationRule['sources'][number]

const sourceKey = companyConditionSourceKey

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

type ConditionPresentation = ReturnType<typeof companyConditionPresentation>

function ConditionContext({ condition, mobile = false }: { condition: ConditionPresentation; mobile?: boolean }) {
  return <div className={`participation-guide__context${mobile ? ' participation-guide__context--mobile' : ''}`}>
    {mobile && <span className="participation-guide__context-label">해당 사업</span>}
    {condition.scope ? <span className="participation-guide__program">
      {condition.scope.split(' / ').map((path, index) => <span className="participation-guide__program-path" key={path}>
        {index > 0 && <span className="participation-guide__visually-hidden"> / </span>}
        <strong className="participation-guide__program-name">{path === '공통' ? '전체 사업 공통' : path}</strong>
      </span>)}
    </span> : <span className="participation-guide__unscoped">{condition.scopeReviewRequired ? '원문 확인 필요' : '-'}</span>}
    {condition.scope && condition.scopeReviewRequired && <span className="participation-guide__scope-review">원문 확인 필요</span>}
    {condition.audience && <span className="participation-guide__role">
      <span>신청 대상:</span> <span className="participation-guide__audience">{condition.audience}</span>
    </span>}
  </div>
}

function Rule({ entry, detail, label, condition }: {
  entry: ParticipationCriterionEntry; detail: DocumentDetail; label: string; condition: ConditionPresentation
}) {
  const item = entry.rule
  const [open, setOpen] = useState(false)
  const [notesOpen, setNotesOpen] = useState(false)
  const id = useId()
  const notesId = useId()
  const hasEvidence = item.sources.some(source => source.excerpt?.trim())
  const hasNotes = condition.notes.length > 0
  return <div className="participation-guide__rule">
    <div className="participation-guide__description">
      <ConditionContext condition={condition} mobile />
      <p className="participation-guide__main">{condition.sentence}</p>
    </div>
    {(hasNotes || hasEvidence) && <div className="participation-guide__actions">
      {hasNotes && <button type="button" className="participation-guide__notes-button"
        aria-label={`${label} 예외·추가 조건 ${condition.notes.length}개 ${notesOpen ? '접기' : '펼치기'}`}
        aria-expanded={notesOpen} aria-controls={notesId} onClick={() => setNotesOpen(value => !value)}>
        <ListChecks size={16} aria-hidden="true" /><span>예외·추가 조건</span>
        <span className="participation-guide__notes-count" aria-hidden="true">{condition.notes.length}</span>
        <ChevronDown size={14} aria-hidden="true" />
      </button>}
      {hasEvidence && <button type="button" className="participation-guide__evidence-button"
        aria-label={`${label} 원문 근거 ${open ? '접기' : '확인'}`} aria-expanded={open} aria-controls={id}
        onClick={() => setOpen(value => !value)}>
        <FileText size={16} aria-hidden="true" /><span>원문 근거 {open ? '접기' : '확인'}</span><ChevronDown size={14} aria-hidden="true" />
      </button>}
    </div>}
    {hasNotes && <div id={notesId} hidden={!notesOpen} className="participation-guide__notes">
      <strong>예외·추가 조건</strong>
      <ul>{condition.notes.map((note, index) => <li key={index}>{note}</li>)}</ul>
    </div>}
    <div id={id} hidden={!open} className="participation-guide__sources">
      {open && <RuleEvidence item={item} detail={detail} />}
    </div>
  </div>
}

export function ParticipationConditions({ detail }: { detail: DocumentDetail }) {
  const rows = companyParticipationRows(detail.participationGuide)
  const hasBusinessDivision = companyHasBusinessDivision(detail.participationGuide)
  const allRows = [...rows.filter(row => row.code === 'BUSINESS_FORM'), ...rows.filter(row => row.code !== 'BUSINESS_FORM')]
    .filter(row => row.mandatory.length || row.preference.length)
  const allEntries = allRows.flatMap(row => [...row.mandatory, ...row.preference])
  const programs = [...new Set(allEntries.flatMap(entry => companyConditionScope(entry)?.split(' / ') ?? []))]
    .filter(value => value !== '공통' && !value.endsWith(' · 공통'))
  const canFilter = programs.length >= 3 && allEntries.length >= 8
  const [programSelection, setProgramSelection] = useState({ detectionId: detail.detectionId, program: '' })
  const activeProgram = activeCompanyConditionProgram(programSelection, detail.detectionId, programs, allEntries.length)
  const selectProgram = (program: string) => setProgramSelection({ detectionId: detail.detectionId, program })
  const visibleRows = allRows.map(row => ({ ...row,
    mandatory: row.mandatory.filter(entry => companyConditionAppliesTo(entry, activeProgram)),
    preference: row.preference.filter(entry => companyConditionAppliesTo(entry, activeProgram)),
  })).filter(row => row.mandatory.length || row.preference.length)
  const visibleCount = visibleRows.reduce((sum, row) => sum + row.mandatory.length + row.preference.length, 0)
  const filterId = useId()
  const headingId = useId()
  const status = detail.participationGuide?.rewriteStatus
  const pending = status === 'PENDING'
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
      {visibleRows.length > 0 ? <>
        {canFilter && <div className="participation-guide__filter">
          <label htmlFor={filterId}>해당 사업</label>
          <select id={filterId} value={activeProgram} onChange={event => selectProgram(event.target.value)}>
            <option value="">전체 사업</option>
            {programs.map(program => <option key={program} value={program}>{program}</option>)}
          </select>
          <span className="participation-guide__filter-count" aria-live="polite">{activeProgram ? `${visibleCount}개 표시 / 전체 ${allEntries.length}개 조건` : `전체 ${allEntries.length}개 조건`}</span>
          {activeProgram && <button type="button" onClick={() => selectProgram('')}>전체 보기</button>}
          {activeProgram && <p>선택한 사업의 조건과 공통 조건을 표시합니다. 사업 구분이 확인되지 않은 조건도 포함합니다.</p>}
        </div>}
        <nav className="participation-guide__index" aria-label="신청 자격 항목 바로가기">
          <span className="participation-guide__index-label" aria-hidden="true">자격 항목 바로가기</span>
          <div className="participation-guide__index-links">{visibleRows.map(row => <a key={row.code} href={`#${headingId}-${row.code}`} onClick={event => {
            event.preventDefault()
            const target = document.getElementById(`${headingId}-${row.code}`)
            target?.scrollIntoView({ block: 'start' })
            target?.focus({ preventScroll: true })
          }}>{row.label}</a>)}</div>
        </nav>
        <div className="participation-guide__table-wrap">
          <table className="participation-guide__table" aria-label="참여기업 필수 조건과 우대사항">
            <colgroup><col className="participation-guide__category-col" /><col className="participation-guide__kind-col" /><col className="participation-guide__program-col" /><col /></colgroup>
            <thead><tr><th scope="col">자격 항목</th><th scope="col">구분</th><th scope="col" className="participation-guide__type-heading">해당 사업</th><th scope="col">신청 조건</th></tr></thead>
            {visibleRows.map(row => {
              const conditions = [...row.mandatory.map(entry => ({ entry, kind: 'mandatory' as const })),
                ...row.preference.map(entry => ({ entry, kind: 'preference' as const }))]
              return <tbody key={row.code} data-criterion={row.code} id={`${headingId}-${row.code}`} tabIndex={-1}>
                {conditions.map(({ entry, kind }, index) => {
                  const condition = companyConditionPresentation(entry, hasBusinessDivision)
                  const excluded = entry.rule.section === 'EXCLUSION'
                  const label = excluded ? '신청 제외' : kind === 'mandatory' ? '필수' : '우대'
                  return <tr key={JSON.stringify([detail.detectionId, entry.rule.scope, entry.rule.audience, entry.rule.text, index])} data-condition-kind={excluded ? 'exclusion' : kind}>
                    {index === 0 && <th scope="rowgroup" rowSpan={conditions.length}>
                      <span className="participation-guide__field-name">{row.label}</span>
                    </th>}
                    <td className="participation-guide__kind"><span className={`participation-guide__kind-badge participation-guide__kind-badge--${excluded ? 'exclusion' : kind}`}>{label}</span></td>
                    <td className="participation-guide__type-cell"><ConditionContext condition={condition} /></td>
                    <td className="participation-guide__condition-cell">
                      <Rule entry={entry} detail={detail} condition={condition}
                        label={row.label + (kind === 'mandatory' ? ' 필수 조건' : ' 우대사항')} />
                    </td>
                  </tr>
                })}
              </tbody>
            })}
          </table>
        </div>
      </> : !pending && <p className="participation-guide__missing">{status === 'UNAVAILABLE'
        ? '신청 조건 정리를 완료하지 못했습니다. 공고 원문을 확인해 주세요.'
        : '정리된 신청 조건이 없습니다. 공고 원문을 확인해 주세요.'}</p>}
    </div>
  </section>
}
