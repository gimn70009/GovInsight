import { Fragment, useId, useState } from 'react'
import { CalendarDays, ChevronDown, CircleHelp, FileText, Banknote, ExternalLink } from 'lucide-react'
import type { DocumentAttachment, DocumentDetail, ProposalPreparationItem, ResponsibleOrganization, SubmissionDocument, SubmissionGuide } from '../api/types'
import { documentPresentation, evidenceAttachment, formatSupportScale, organizationEvidenceAttachment, summaryDocuments, summaryValue } from '../utils/businessSummary'
import './BusinessSummary.css'

const requirementLabels = {
  MANDATORY: '필수', CONDITIONAL: '해당 시 제출', OPTIONAL: '선택', RECOMMENDED: '권장',
} as const
const stageLabels = {
  APPLICATION: '신청 시', EVALUATION: '평가 시', POST_SELECTION: '선정 후',
  AGREEMENT: '협약 시', EXECUTION: '수행 중', REPORTING: '보고 시',
} as const

const submissionUnknownHelp = {
  stage: {
    label: '제출 시기 확인',
    title: '언제 제출하는지 확인이 필요해요',
    description: '이 서류를 신청할 때 내는지, 평가·선정 이후나 협약 때 내는지 확인하지 못했습니다.',
  },
  requirement: {
    label: '제출 여부 확인',
    title: '어떤 경우에 제출하는지 확인이 필요해요',
    description: '이 서류가 필수인지, 특정 대상만 제출하는지, 선택 제출인지 확인하지 못했습니다.',
  },
} as const

function MissingValue({ hint }: { hint?: string }) {
  const [expanded, setExpanded] = useState(false)
  const hintId = useId()
  const label = <><CircleHelp size={14} aria-hidden="true" />확인 필요</>
  return hint ? <div className="business-summary__missing-help">
    <button type="button" className="business-summary__missing" aria-label="확인 필요 안내"
      aria-expanded={expanded} aria-controls={hintId} onClick={() => setExpanded(value => !value)}>
      {label}<ChevronDown className="business-summary__missing-chevron" size={12} aria-hidden="true" />
    </button>
    <p id={hintId} hidden={!expanded}>{hint}</p>
  </div> : <span className="business-summary__missing">{label}</span>
}

function SubmissionDocumentRow({ item, detail }: { item: SubmissionDocument; detail: DocumentDetail }) {
  const [evidenceName, setEvidenceName] = useState<string | null>(null)
  const [conditionsExpanded, setConditionsExpanded] = useState(false)
  const [unknownField, setUnknownField] = useState<keyof typeof submissionUnknownHelp | null>(null)
  const unknownId = useId()
  const unknownHelp = unknownField === 'stage' && !item.stage ? submissionUnknownHelp.stage
    : unknownField === 'requirement' && !item.requirementLevel ? submissionUnknownHelp.requirement : null
  const evidenceId = useId()
  const conditionsId = useId()
  const { names, conditions, formats } = documentPresentation(item)
  const conditional = item.requirementLevel === 'CONDITIONAL'
  const level = item.requirementLevel?.toLowerCase() ?? 'unknown'
  const requirement = item.requirementLevel ? requirementLabels[item.requirementLevel] : '제출 여부 확인'
  const conditionToggle = (label: string, className: string) => <button type="button" className={className}
    aria-label={`${names.join(' · ')} ${label} 조건 보기`} aria-expanded={conditionsExpanded} aria-controls={conditionsId}
    onClick={() => setConditionsExpanded(value => !value)}>
    {label}<ChevronDown size={13} aria-hidden="true" />
  </button>
  const unknownToggle = (field: keyof typeof submissionUnknownHelp, className: string) => <button type="button"
    id={`${unknownId}-${field}`} className={`${className} is-unknown`}
    aria-label={`${names.join(' · ')} ${submissionUnknownHelp[field].label} 안내`}
    aria-expanded={unknownField === field} aria-controls={unknownId}
    onClick={() => setUnknownField(value => value === field ? null : field)}>
    <CircleHelp size={13} aria-hidden="true" />{submissionUnknownHelp[field].label}<ChevronDown size={13} aria-hidden="true" />
  </button>
  return <Fragment>
    <tr className="business-summary__document-row">
      <th scope="row">
        <div className="business-summary__document-names">
          {names.map(name => <span key={name} className="business-summary__document-name-group">
            <span className="business-summary__document-name">{name}</span>
            <button type="button" className="business-summary__organization-toggle"
              aria-label={`${name} 근거 보기`} aria-expanded={evidenceName === name} aria-controls={evidenceId}
              onClick={() => setEvidenceName(value => value === name ? null : name)}>
              근거 보기<ChevronDown size={13} aria-hidden="true" />
            </button>
          </span>)}
        </div>
      </th>
      <td>
        <div className="business-summary__document-meta">
          {!item.requirementLevel ? unknownToggle('requirement', 'business-summary__requirement')
            : conditional ? conditionToggle(requirement, `business-summary__requirement is-${level}`)
              : <span className={`business-summary__requirement is-${level}`}>{requirement}</span>}
          {item.stage ? <span className="business-summary__stage">{stageLabels[item.stage]}</span>
            : unknownToggle('stage', 'business-summary__stage')}
          {formats.map(format => <span key={format} className="business-summary__format">{format}</span>)}
          {!conditional && conditions.length > 0 && conditionToggle('제출 조건', 'business-summary__condition-toggle')}
        </div>
        <div id={unknownId} hidden={!unknownHelp} className="business-summary__document-help"
          role="region" aria-labelledby={unknownField ? `${unknownId}-${unknownField}` : undefined}>
          {unknownHelp && <>
            <strong>{unknownHelp.title}</strong>
            <p>{unknownHelp.description}</p>
            <p>원문에 안내가 있어도 자동으로 구분하지 못했을 수 있습니다. 서류명 옆 ‘근거 보기’와 공고 원문을 함께 확인해 주세요.</p>
          </>}
        </div>
        {conditions.length > 0 && <div id={conditionsId} hidden={!conditionsExpanded} className="business-summary__document-conditions">
          <strong>{conditional ? '이 경우에 제출해 주세요' : '함께 확인할 제출 조건'}</strong>
          <ul>{conditions.map(condition => <li key={condition}>{condition}</li>)}</ul>
        </div>}
      </td>
    </tr>
    {evidenceName && <tr className="business-summary__document-evidence-row">
      <td colSpan={2}><div id={evidenceId} className="business-summary__document-source">
        <strong>{evidenceName} · 원문 근거</strong>
        <SubmissionSource source={item.source} detail={detail} />
      </div></td>
    </tr>}
  </Fragment>
}

function SubmissionSource({ source, detail }: { source: ProposalPreparationItem['source']; detail: DocumentDetail }) {
  const file = evidenceAttachment({ source }, detail.attachments)
  return <>
    <span>{source?.origin === 'NOTICE_BODY' ? '공고 본문' : source?.attachmentName || '첨부 문서'}</span>
    {(source?.sectionTitle || source?.location) && <small>{[source?.sectionTitle, source?.location].filter(Boolean).join(' · ')}</small>}
    <blockquote>{source?.excerpt}</blockquote>
    {file && <a href={file.downloadUrl} target="_blank" rel="noreferrer">근거 문서 열기<ExternalLink size={12} aria-hidden="true" /></a>}
  </>
}

function SubmissionNoteRow({ note, detail }: { note: SubmissionGuide['notes'][number]; detail: DocumentDetail }) {
  const [expanded, setExpanded] = useState(false)
  const evidenceId = useId()
  return <li>
    {note.appliesTo && <span className="business-summary__note-scope">{note.appliesTo}</span>}
    <div className="business-summary__note-line">
      <p>{note.text.replace(/^[*※◦]+\s*/u, '')}</p>
      <button type="button" className="business-summary__organization-toggle"
        aria-label={`${note.appliesTo ? `${note.appliesTo} · ` : ''}${note.text} 근거 보기`}
        aria-expanded={expanded} aria-controls={evidenceId} onClick={() => setExpanded(value => !value)}>
        근거 보기<ChevronDown size={13} aria-hidden="true" />
      </button>
    </div>
    <div id={evidenceId} hidden={!expanded} className="business-summary__note-source">
      <SubmissionSource source={note.source} detail={detail} />
    </div>
  </li>
}

function OrganizationRow({ item, attachments }: { item: ResponsibleOrganization; attachments: DocumentAttachment[] }) {
  const [expanded, setExpanded] = useState(false)
  const evidenceId = useId()
  const file = organizationEvidenceAttachment(item, attachments)
  return <li>
    <div className="business-summary__organization">
      <span className="business-summary__organization-role">{item.role}</span>
      <strong>{item.name}</strong>
      <button type="button" className="business-summary__organization-toggle"
        aria-label={`${item.name} ${item.role} 기관 근거 보기`} aria-expanded={expanded} aria-controls={evidenceId}
        onClick={() => setExpanded(value => !value)}>
        근거 보기<ChevronDown size={13} aria-hidden="true" />
      </button>
    </div>
    <div id={evidenceId} hidden={!expanded} className="business-summary__organization-source">
      <span>{item.sourceType === 'NOTICE_BODY' ? '공고 본문' : item.attachmentName || '첨부 문서'}</span>
      <blockquote>{item.excerpt}</blockquote>
      {file && <a href={file.downloadUrl} target="_blank" rel="noreferrer">근거 문서 열기<ExternalLink size={12} aria-hidden="true" /></a>}
    </div>
  </li>
}

function ResponsibleOrganizations({ detail }: { detail: DocumentDetail }) {
  const organizations = (detail.responsibleOrganizations ?? [])
    .filter(item => summaryValue(item.name) && summaryValue(item.role) && summaryValue(item.excerpt))
  return organizations.length > 0 ? <ul className="business-summary__organizations">
    {organizations.map((item, index) => <OrganizationRow
      key={`${detail.detectionId}-${item.role}-${item.name}-${index}`} item={item} attachments={detail.attachments} />)}
  </ul> : <MissingValue key={detail.detectionId} hint="공고 본문과 첨부파일에서 담당기관을 확인하지 못했습니다. 공고 원문을 확인해 주세요." />
}

export function BusinessSummary({ detail }: { detail: DocumentDetail }) {
  const analysis = detail.analysis
  const purpose = summaryValue(analysis?.businessSummary?.purpose) ?? summaryValue(analysis?.summary)
  const supportScale = summaryValue(analysis?.businessSummary?.supportScale)
  const deadline = summaryValue(analysis?.applicationDeadline)
    ?? summaryValue(analysis?.proposal.preparation?.applicationDeadline)
  const documents = summaryDocuments(analysis, detail.submissionGuide)
  const notes = (detail.submissionGuide?.notes ?? []).filter(note => note.text.trim() && note.source?.excerpt?.trim())
  const preparingDocuments = detail.analysisPending && analysis?.proposal.draftStatus === 'GENERATING'

  return <section className="business-summary" aria-labelledby="business-summary-title">
    <div className="business-summary__heading">
      <div><h3 id="business-summary-title">사업 정보 한눈에 보기</h3><p>저장된 공고와 분석을 기준으로 정리한 정보입니다.</p></div>
      <span className="business-summary__heading-icon" aria-hidden="true"><FileText size={22} /></span>
    </div>
    {!analysis && <p className="business-summary__status" role="status">
      {detail.analysisPending ? 'AI 분석을 준비하고 있어요. 완료되면 정보가 자동으로 갱신됩니다.' : 'AI 분석 결과가 없습니다. 확인되지 않은 정보는 공고 원문을 참고해 주세요.'}
    </p>}
    <div className="business-summary__table-wrap">
      <table className="business-summary__facts" aria-label="사업 핵심 정보">
        <tbody>
          <tr><th scope="row">사업 담당기관</th><td><ResponsibleOrganizations detail={detail} /></td></tr>
          <tr><th scope="row">사업명</th><td><strong className="business-summary__name">{detail.title}</strong></td></tr>
          <tr className="business-summary__emphasis"><th scope="row"><span><Banknote size={16} aria-hidden="true" />지원규모</span></th><td>{supportScale ? <p className="business-summary__amount">{formatSupportScale(supportScale)}</p> : <MissingValue />}</td></tr>
          <tr><th scope="row">사업 설명</th><td>{purpose ? <p className="business-summary__description">{purpose}</p> : <MissingValue />}</td></tr>
          <tr className="business-summary__emphasis"><th scope="row"><span><CalendarDays size={16} aria-hidden="true" />제출기한</span></th><td>{deadline ? <p className="business-summary__deadline">{deadline}</p> : <MissingValue />}</td></tr>
        </tbody>
      </table>
    </div>
    <div className="business-summary__documents-heading"><h4>제출서류</h4>{documents.length > 0 && <span>확인된 서류 {documents.length}건</span>}</div>
    {documents.length > 0 && notes.length > 0 && <aside className="business-summary__submission-notes" aria-label="제출 유의사항">
      <strong>제출 유의사항</strong>
      <ul>{notes.map((note, index) => <SubmissionNoteRow key={`${detail.detectionId}-${note.text}-${index}`} note={note} detail={detail} />)}</ul>
    </aside>}
    {documents.length > 0 ? <div className="business-summary__table-wrap">
      <table className="business-summary__documents" aria-label="제출서류 안내">
        <thead><tr><th scope="col">서류명</th><th scope="col">제출 안내</th></tr></thead>
        <tbody>{documents.map((item, index) => <SubmissionDocumentRow
          key={`${detail.detectionId}-${item.title}-${index}`} item={item} detail={detail} />)}</tbody>
      </table>
    </div> : <div className="business-summary__documents-empty">
      <FileText size={21} aria-hidden="true" /><div><strong>{preparingDocuments ? '제출서류를 정리하고 있습니다' : '제출서류 확인 필요'}</strong>
        <p>{preparingDocuments ? '분석이 완료되면 자동으로 표시됩니다.' : '저장된 공고 본문·첨부와 분석에서 제출서류를 확인하지 못했습니다. 공고 원문을 확인해 주세요.'}</p></div>
    </div>}
    {documents.length > 0 && <p className="business-summary__footnote">원문 근거가 확인된 서류입니다. 전체 제출 목록과 조건은 공고 원문을 함께 확인해 주세요.</p>}
  </section>
}
