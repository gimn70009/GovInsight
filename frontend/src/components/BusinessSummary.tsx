import { BusinessTypeRow } from './BusinessTypeRow'
import { ParticipationConditions } from './ParticipationConditions'
import { SectionStatus } from './SectionStatus'
import { Fragment, useId, useState } from 'react'
import { CalendarDays, ChevronDown, CircleHelp, FileText, Banknote, ExternalLink, LayoutList } from 'lucide-react'
import type { DocumentAttachment, DocumentDetail, ProposalPreparationItem, ResponsibleOrganization, SubmissionDocument, SubmissionGuide } from '../api/types'
import { documentPresentation, evidenceAttachment, formatSummaryText, formatSupportScale, organizationEvidenceAttachment, summaryDocuments, summaryValue } from '../utils/businessSummary'
import './BusinessSummary.css'
import { submissionNoteGroups, submissionNoteKey } from '../utils/submissionNotes'
import { missingAnalysisNotice } from '../utils/analysisStatus'

const requirementLabels = {
  MANDATORY: '필수', CONDITIONAL: '해당 시 제출', OPTIONAL: '선택', RECOMMENDED: '권장',
} as const
const stageLabels = {
  APPLICATION: '신청 시', EVALUATION: '평가 시', POST_SELECTION: '선정 후',
  AGREEMENT: '협약 시', EXECUTION: '수행 중', REPORTING: '보고 시',
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

function SubmissionDocumentRow({ item, detail, notes, commonNotes }: { item: SubmissionDocument; detail: DocumentDetail; notes: SubmissionGuide['notes']; commonNotes: SubmissionGuide['notes'] }) {
  const [evidenceOpen, setEvidenceOpen] = useState(false)
  const [conditionsExpanded, setConditionsExpanded] = useState(false)
  const [formatsExpanded, setFormatsExpanded] = useState(false)
  const evidenceId = useId()
  const conditionsId = useId()
  const formatsId = useId()
  const { names, conditions: originalConditions, formats } = documentPresentation(item)
  const documentLabel = names.join(', ')
  const displayedNotes = new Set([...notes, ...commonNotes].map(note => submissionNoteKey(note.text)))
  const conditions = originalConditions.filter(condition => !displayedNotes.has(submissionNoteKey(condition)))
  const hasGuidance = conditions.length > 0 || notes.length > 0
  const conditional = item.requirementLevel === 'CONDITIONAL' && hasGuidance
  const guidance = <>
    {conditions.length > 0 && <div className="business-summary__document-conditions">
      <strong>제출 조건</strong>
      <ul>{conditions.map(condition => <li key={condition}>{condition}</li>)}</ul>
    </div>}
    {notes.length > 0 && <ul className="business-summary__document-notes" aria-label={`${names.join(' · ')} 유의사항`}>
      {notes.map((note, index) => <SubmissionNoteRow key={`${note.text}-${index}`} note={note} detail={detail} showScope={false} />)}
    </ul>}
  </>
  return <Fragment>
    <tr className="business-summary__document-row">
      <th scope="row">
        <div className="business-summary__document-names">
          <span className="business-summary__document-name">{documentLabel}</span>
          <button type="button" className="business-summary__organization-toggle"
            aria-label={`${documentLabel} 원문 근거 ${evidenceOpen ? '접기' : '확인'}`} aria-expanded={evidenceOpen} aria-controls={evidenceId}
            onClick={() => setEvidenceOpen(value => !value)}>
            <FileText size={15} aria-hidden="true" /><span>원문 근거 {evidenceOpen ? '접기' : '확인'}</span><ChevronDown size={14} aria-hidden="true" />
          </button>
        </div>
      </th>
      <td>
        <div className="business-summary__document-meta">
          {conditional ? <button type="button" className="business-summary__requirement is-conditional"
            aria-label={`${names.join(' · ')} 해당 시 제출 조건 ${conditionsExpanded ? '접기' : '보기'}`}
            aria-expanded={conditionsExpanded} aria-controls={conditionsId}
            onClick={() => setConditionsExpanded(value => !value)}>
            <span>해당 시 제출</span><ChevronDown size={13} aria-hidden="true" />
          </button> : item.requirementLevel && <span className={`business-summary__requirement is-${item.requirementLevel.toLowerCase()}`}>{requirementLabels[item.requirementLevel]}</span>}
          {item.stage && <span className="business-summary__stage">{stageLabels[item.stage]}</span>}
          {formats.length > 0 && <button type="button" className="business-summary__formats-toggle"
            aria-label={`${names.join(' · ')} 파일 형식 ${formatsExpanded ? '접기' : '보기'}`} aria-expanded={formatsExpanded} aria-controls={formatsId}
            onClick={() => setFormatsExpanded(value => !value)}>
            <FileText size={13} aria-hidden="true" /><span>파일 형식</span><ChevronDown size={13} aria-hidden="true" />
          </button>}
        </div>
        {formats.length > 0 && <div id={formatsId} hidden={!formatsExpanded} className="business-summary__document-details"
          role="region" aria-label={`${names.join(' · ')} 파일 형식`}>
          <div className="business-summary__document-formats">
            <strong>파일 형식</strong>
            <ul aria-label="제출 파일 형식">{formats.map(format => <li key={format} className="business-summary__format">{format}</li>)}</ul>
          </div>
        </div>}
        {conditional && <div id={conditionsId} hidden={!conditionsExpanded} className="business-summary__document-details"
          role="region" aria-label={`${names.join(' · ')} 제출 조건`}>{guidance}</div>}
      </td>
    </tr>
    {evidenceOpen && <tr className="business-summary__document-evidence-row">
      <td colSpan={2}><div id={evidenceId} className="business-summary__document-source">
        <strong>{documentLabel} · 원문 근거</strong>
        <SubmissionSource source={item.source} detail={detail} />
        {!conditional && hasGuidance && <div className="business-summary__document-details"
          role="region" aria-label={`${names.join(' · ')} 제출 안내`}>{guidance}</div>}
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

function SubmissionNoteRow({ note, detail, showScope = true }: { note: SubmissionGuide['notes'][number]; detail: DocumentDetail; showScope?: boolean }) {
  const [expanded, setExpanded] = useState(false)
  const evidenceId = useId()
  return <li>
    {showScope && note.appliesTo && <span className="business-summary__note-scope">{note.appliesTo}</span>}
    <div className="business-summary__note-line">
      <p>{note.text.replace(/^[*※◦]+\s*/u, '')}</p>
      <button type="button" className="business-summary__organization-toggle"
        aria-label={`${note.appliesTo ? `${note.appliesTo} · ` : ''}${note.text} 원문 근거 ${expanded ? '접기' : '확인'}`}
        aria-expanded={expanded} aria-controls={evidenceId} onClick={() => setExpanded(value => !value)}>
        <FileText size={15} aria-hidden="true" /><span>원문 근거 {expanded ? '접기' : '확인'}</span><ChevronDown size={14} aria-hidden="true" />
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
        aria-label={`${item.name} ${item.role} 기관 원문 근거 ${expanded ? '접기' : '확인'}`} aria-expanded={expanded} aria-controls={evidenceId}
        onClick={() => setExpanded(value => !value)}>
        <FileText size={15} aria-hidden="true" /><span>원문 근거 {expanded ? '접기' : '확인'}</span><ChevronDown size={14} aria-hidden="true" />
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
  const headingId = useId()
  const analysis = detail.analysis
  const analysisNotice = missingAnalysisNotice(detail)
  const purpose = summaryValue(analysis?.businessSummary?.purpose) ?? summaryValue(analysis?.summary)
  const supportScale = summaryValue(analysis?.businessSummary?.supportScale)
  const deadline = summaryValue(analysis?.applicationDeadline)
    ?? summaryValue(analysis?.proposal.preparation?.applicationDeadline)
  const documents = summaryDocuments(analysis, detail.submissionGuide)
  const { common: notes, byDocument: documentNotes, unassigned } = submissionNoteGroups(documents, detail.submissionGuide?.notes)
  const notesStatus = detail.submissionGuide?.notesStatus
  const preparingDocuments = detail.analysisPending && analysis?.proposal.draftStatus === 'GENERATING'
  const submissionPending = notesStatus === 'PENDING' || (documents.length === 0 && preparingDocuments)

  return <div className="business-summary">
    <section className="business-summary__card" aria-labelledby={headingId}>
      <header className="business-summary__heading">
        <span className="business-summary__heading-icon" aria-hidden="true"><LayoutList size={22} /></span>
        <div className="business-summary__heading-text"><h3 id={headingId}>사업 정보 한눈에 보기</h3><p>저장된 공고와 분석을 기준으로 정리한 정보입니다.</p></div>
      </header>
      <div className="business-summary__section-body">
        {!analysis && <div className={`business-summary__status${analysisNotice.pending ? '' : ' business-summary__status--failed'}`} role="status">
          <strong>{analysisNotice.title}</strong>
          <p>{analysisNotice.description}</p>
        </div>}
        <div className="business-summary__table-wrap">
          <table className="business-summary__facts" aria-label="사업 핵심 정보">
            <tbody>
              <BusinessTypeRow detail={detail} />
              <tr><th scope="row">사업 담당기관</th><td><ResponsibleOrganizations detail={detail} /></td></tr>
              <tr><th scope="row">사업명</th><td><strong className="business-summary__name">{detail.title}</strong></td></tr>
              <tr className="business-summary__emphasis"><th scope="row"><span><Banknote size={16} aria-hidden="true" />지원규모</span></th><td>{supportScale ? <p className="business-summary__amount">{formatSummaryText(formatSupportScale(supportScale))}</p> : <MissingValue />}</td></tr>
              <tr><th scope="row">사업 설명</th><td>{purpose ? <p className="business-summary__description">{formatSummaryText(purpose)}</p> : <MissingValue />}</td></tr>
              <tr className="business-summary__emphasis"><th scope="row"><span><CalendarDays size={16} aria-hidden="true" />제출기한</span></th><td>{deadline ? <p className="business-summary__deadline">{formatSummaryText(deadline)}</p> : <MissingValue />}</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>
    <section className="business-summary__card" aria-labelledby={headingId + '-documents'}>
      <header className="business-summary__heading business-summary__documents-heading">
        <span className="business-summary__heading-icon" aria-hidden="true"><FileText size={22} /></span>
        <div className="business-summary__heading-text"><h3 id={headingId + '-documents'}>제출서류</h3><p>제출 대상과 시기를 확인하고, 라벨을 눌러 조건과 파일 형식을 살펴보세요.</p></div>
        {documents.length > 0 && <span className="business-summary__heading-count">확인된 서류 {documents.length}건</span>}
      </header>
      <div className="business-summary__section-body">
        {submissionPending && <SectionStatus state="loading" compact={documents.length > 0}
          title={documents.length > 0 ? '제출 유의사항을 확인하고 있습니다' : '제출 정보를 확인하고 있습니다'}
          description="확인이 끝나면 자동으로 반영됩니다." />}
        {notes.length > 0 && <aside className="business-summary__submission-notes" aria-label="제출 유의사항">
          <strong>제출 유의사항</strong>
          <ul>{notes.map((note, index) => <SubmissionNoteRow key={`${detail.detectionId}-${note.text}-${index}`} note={note} detail={detail} />)}</ul>
        </aside>}
        {documents.length > 0 ? <div className="business-summary__table-wrap">
          <table className="business-summary__documents" aria-label="제출서류 안내">
            <thead><tr><th scope="col">서류명</th><th scope="col">제출 안내</th></tr></thead>
            <tbody>{documents.map((item, index) => <SubmissionDocumentRow
              key={`${detail.detectionId}-${item.title}-${index}`} item={item} detail={detail} notes={documentNotes[index]} commonNotes={notes} />)}</tbody>
          </table>
        </div> : !submissionPending && <SectionStatus state="empty"
          title="제출서류는 원문에서 확인해 주세요" description="정리된 제출서류 목록을 찾지 못했습니다." />}
        {unassigned.length > 0 && <aside className="business-summary__submission-notes business-summary__unassigned-notes" aria-label="서류별 추가 안내">
          <strong>서류별 추가 안내</strong>
          <ul>{unassigned.map((note, index) => <SubmissionNoteRow key={`${detail.detectionId}-${note.text}-${index}`} note={note} detail={detail} />)}</ul>
        </aside>}
        {documents.length > 0 && <p className="business-summary__footnote">원문 근거가 확인된 서류입니다. 전체 제출 목록과 조건은 공고 원문을 함께 확인해 주세요.</p>}
      </div>
    </section>
    <ParticipationConditions key={detail.detectionId} detail={detail} />
  </div>
}
