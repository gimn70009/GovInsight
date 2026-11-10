import { SectionStatus } from './SectionStatus'
import { useId, useState } from 'react'
import { BadgeCheck, BriefcaseBusiness, Building2, CalendarDays, ChevronDown, ClipboardCheck, ExternalLink, Factory, FileText, Landmark, TriangleAlert, TrendingUp } from 'lucide-react'
import type { DocumentDetail, ParticipationRule, ParticipationCriterionEntry } from '../api/types'
import { evidenceAttachment } from '../utils/businessSummary'
import { companyParticipationRows, companyParticipationExclusions, companyHasBusinessDivision, companyConditionSourceKey, companyConditionPresentation, companyConditionScope, companyConditionAppliesTo, activeCompanyConditionProgram } from '../utils/companyParticipation'
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

function EvidencePanel({ item, detail, defaultOpen }: {
  item: ParticipationRule; detail: DocumentDetail; defaultOpen: boolean
}) {
  const [open, setOpen] = useState(defaultOpen)
  const id = useId()
  return <div className="participation-guide__evidence-panel">
    <button type="button" className="participation-guide__source-toggle"
      aria-expanded={open} aria-controls={id} onClick={() => setOpen(value => !value)}>
      <FileText size={16} aria-hidden="true" />
      <span>원문 근거</span>
      <span className="participation-guide__source-action">{open ? '접기' : '펼치기'}<ChevronDown size={15} aria-hidden="true" /></span>
    </button>
    <div id={id} hidden={!open} className="participation-guide__evidence-content">
      {open && <RuleEvidence item={item} detail={detail} />}
    </div>
  </div>
}

type ConditionPresentation = ReturnType<typeof companyConditionPresentation>

const criterionIcons = {
  BUSINESS_FORM: Building2,
  BUSINESS_AGE: CalendarDays,
  REVENUE: TrendingUp,
  IP: BadgeCheck,
  SECTOR: Factory,
  EXPERIENCE: BriefcaseBusiness,
  CREDIT: Landmark,
}

function ConditionContext({ condition }: { condition: ConditionPresentation }) {
  if (!condition.scope && !condition.scopeReviewRequired) return null
  return <span className="participation-guide__context">
    <span className="participation-guide__context-label">적용 사업</span>
    {condition.scope ? <span className="participation-guide__program">
      {condition.scope.split(' / ').map((path, index) => <span className="participation-guide__program-path" key={path}>
        {index > 0 && <span aria-hidden="true"> / </span>}
        {path === '공통' ? '전체 사업 공통' : path}
      </span>)}
    </span> : <span className="participation-guide__unscoped">원문 확인 필요</span>}
    {condition.scope && condition.scopeReviewRequired && <span className="participation-guide__scope-review">원문 확인 필요</span>}
  </span>
}

function Rule({ entry, detail, label, condition, number, kind }: {
  entry: ParticipationCriterionEntry; detail: DocumentDetail; label: string; condition: ConditionPresentation
  number?: number; kind: 'mandatory' | 'preference' | 'exclusion'
}) {
  const item = entry.rule
  const [open, setOpen] = useState(false)
  const id = useId()
  const hasEvidence = item.sources.some(source => source.excerpt?.trim())
  const hasNotes = condition.notes.length > 0
  const expandable = hasNotes || hasEvidence
  const kindLabel = kind === 'exclusion' ? '지원 제외' : kind === 'preference' ? '우대' : '필수'
  const content = <>
    {number != null && <span className="participation-guide__number" aria-hidden="true">{number}</span>}
    <span className="participation-guide__copy">
      <span className="participation-guide__meta">
        <span className={`participation-guide__kind-badge participation-guide__kind-badge--${kind}`}>{kindLabel}</span>
        <ConditionContext condition={condition} />
      </span>
      <span className="participation-guide__main">{condition.sentence}</span>
    </span>
    {expandable && <span className="participation-guide__reveal" aria-hidden="true">
      <span>{open ? '접기' : hasNotes ? '상세' : '원문'}</span><ChevronDown size={16} />
    </span>}
  </>
  const className = `participation-guide__condition${number == null ? ' participation-guide__condition--single' : ''}`
  return <div className="participation-guide__rule" data-condition-kind={kind}>
    {expandable ? <button type="button" className={`${className} participation-guide__condition-toggle`}
      aria-label={`${label}: ${condition.sentence} 상세 ${open ? '접기' : '보기'}${hasNotes ? `, 세부 설명 ${condition.notes.length}개` : ''}`}
      aria-expanded={open} aria-controls={id} onClick={() => setOpen(value => !value)}>
      {content}
    </button> : <div className={className}>{content}</div>}
    {expandable && <div id={id} hidden={!open} className={`participation-guide__details${number != null ? " participation-guide__details--numbered" : ""}`}>
      {hasNotes && <div className="participation-guide__notes">
        <strong>세부 설명</strong>
        <ul>{condition.notes.map((note, index) => <li key={index}>{note}</li>)}</ul>
      </div>}
      {open && hasEvidence && <EvidencePanel item={item} detail={detail} defaultOpen={!hasNotes} />}
    </div>}
  </div>
}

export function ParticipationConditions({ detail }: { detail: DocumentDetail }) {
  const rows = companyParticipationRows(detail.participationGuide)
  const hasBusinessDivision = companyHasBusinessDivision(detail.participationGuide)
  const allRows = [...rows.filter(row => row.code === 'BUSINESS_FORM'), ...rows.filter(row => row.code !== 'BUSINESS_FORM')]
    .filter(row => row.mandatory.length || row.preference.length)
  const exclusions = companyParticipationExclusions(detail.participationGuide)
  const allEntries = [...allRows.flatMap(row => [...row.mandatory, ...row.preference]), ...exclusions]
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
  const visibleExclusions = exclusions.filter(entry => companyConditionAppliesTo(entry, activeProgram))
  const visibleCount = visibleExclusions.length + visibleRows.reduce((sum, row) => sum + row.mandatory.length + row.preference.length, 0)
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
      {pending && <SectionStatus state="loading" compact={visibleCount > 0}
        title="신청 자격을 확인하고 있습니다" description="확인이 끝나면 자동으로 반영됩니다." />}
      {visibleCount > 0 ? <>
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
        {visibleRows.length >= 4 && <nav className="participation-guide__index" aria-label="신청 자격 항목 바로가기">
          <span className="participation-guide__index-label" aria-hidden="true">자격 항목 바로가기</span>
          <div className="participation-guide__index-links">{visibleRows.map(row => <a key={row.code} href={`#${headingId}-${row.code}`} onClick={event => {
            event.preventDefault()
            const target = document.getElementById(`${headingId}-${row.code}`)
            target?.scrollIntoView({ block: 'start' })
            target?.focus({ preventScroll: true })
          }}>{row.label}</a>)}</div>
        </nav>}
        <div className="participation-guide__groups">
          {visibleRows.map(row => {
            const conditions = [...row.mandatory, ...row.preference]
            const Icon = criterionIcons[row.code]
            const groupId = `${headingId}-${row.code}`
            return <section key={row.code} data-criterion={row.code} id={groupId} tabIndex={-1}
              className="participation-guide__group" aria-labelledby={`${groupId}-title`}>
              <header className="participation-guide__group-heading">
                <Icon size={18} aria-hidden="true" />
                <h4 id={`${groupId}-title`}>{row.label}</h4>
                <span className="participation-guide__group-count">조건 {conditions.length}개</span>
              </header>
              <ol className="participation-guide__conditions">
                {conditions.map((entry, index) => {
                  const condition = companyConditionPresentation(entry, hasBusinessDivision)
                  const kind = entry.rule.section === 'PREFERENCE' ? 'preference' : 'mandatory'
                  return <li key={JSON.stringify([detail.detectionId, entry.rule.scope, entry.rule.audience, entry.rule.text, index])}>
                    <Rule entry={entry} detail={detail} condition={condition} kind={kind}
                      number={conditions.length > 1 ? index + 1 : undefined} label={`${row.label} ${index + 1}번 조건`} />
                  </li>
                })}
              </ol>
            </section>
          })}
          {visibleExclusions.length > 0 && <section className="participation-guide__group participation-guide__exclusions"
            aria-labelledby={`${headingId}-exclusions`}>
            <header className="participation-guide__group-heading">
              <TriangleAlert size={18} aria-hidden="true" />
              <h4 id={`${headingId}-exclusions`}>지원 제외 대상</h4>
              <span className="participation-guide__group-count">{visibleExclusions.length}개</span>
            </header>
            <ol className="participation-guide__conditions">
              {visibleExclusions.map((entry, index) => <li key={JSON.stringify([detail.detectionId, entry.rule.scope, entry.rule.text, index])}>
                <Rule entry={entry} detail={detail} condition={companyConditionPresentation(entry, hasBusinessDivision)}
                  kind="exclusion" number={visibleExclusions.length > 1 ? index + 1 : undefined}
                  label={`지원 제외 ${index + 1}번 조건`} />
              </li>)}
            </ol>
          </section>}
        </div>
      </> : !pending && <SectionStatus state={status === 'UNAVAILABLE' ? 'unavailable' : 'empty'}
        title={status === 'UNAVAILABLE' ? '신청 자격 확인을 완료하지 못했습니다' : '신청 자격은 원문에서 확인해 주세요'}
        description={status === 'UNAVAILABLE' ? '공고 원문에서 지원 대상과 신청 요건을 확인해 주세요.' : '정리된 신청 조건을 찾지 못했습니다.'} />}
    </div>
  </section>
}
