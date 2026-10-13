import type { DocumentAnalysis, DocumentAttachment, ProposalPreparationItem, ResponsibleOrganization, SubmissionDocument, SubmissionGuide } from '../api/types'

// Only whole-field placeholders are missing. Partial conditions and amounts stay intact.
export function summaryValue(value: string | null | undefined): string | null {
  const text = value?.trim()
  if (!text || /^(?:미확인|확인 필요|원문 확인 필요|정보 없음|원문에서 확인하지 못했습니다)[.!]?$/u.test(text)) return null
  return text
}

export function summaryDocuments(analysis: DocumentAnalysis | null, guide?: SubmissionGuide): SubmissionDocument[] {
  const items = guide?.documents ?? analysis?.proposal.preparation?.submissionDocuments ?? []
  return items.filter(item =>
    item.title.trim()
    && item.requirementLevel !== 'RECOMMENDED'
    && ['NOTICE_BODY', 'ATTACHMENT'].includes(item.source?.origin ?? '')
    && item.source?.excerpt?.trim(),
  )
}

export function evidenceAttachment(item: Pick<ProposalPreparationItem, 'source'>, attachments: DocumentAttachment[]): DocumentAttachment | null {
  if (item.source?.origin !== 'ATTACHMENT' || !item.source.attachmentName) return null
  return attachmentByName(item.source.attachmentName, attachments)
}

export function organizationEvidenceAttachment(item: ResponsibleOrganization, attachments: DocumentAttachment[]): DocumentAttachment | null {
  if (item.sourceType !== 'ATTACHMENT' || !item.attachmentName) return null
  return attachmentByName(item.attachmentName, attachments)
}

function attachmentByName(name: string, attachments: DocumentAttachment[]): DocumentAttachment | null {
  const matches = attachments.filter(file => file.fileName.trim() === name.trim())
  if (matches.length !== 1) return null
  try {
    return ['http:', 'https:'].includes(new URL(matches[0].downloadUrl).protocol) ? matches[0] : null
  } catch {
    return null
  }
}
