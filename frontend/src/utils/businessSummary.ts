import type { DocumentAnalysis, DocumentAttachment, ProposalPreparationItem, ResponsibleOrganization, SubmissionDocument, SubmissionGuide } from '../api/types'

// Only whole-field placeholders are missing. Partial conditions and amounts stay intact.
export function summaryValue(value: string | null | undefined): string | null {
  const text = value?.trim()
  if (!text || /^(?:미확인|확인 필요|원문 확인 필요|정보 없음|원문에서 확인하지 못했습니다)[.!]?$/u.test(text)) return null
  return text
}

// Use familiar clause separators in summary prose without changing stored text or evidence.
export function formatSummaryText(value: string): string {
  return value.replace(/[ \t]*[;；]+[ \t]*/gu, ', ')
}

const wonUnitPowers: Record<string, number> = {
  '': 0, '백': 2, '천': 3, '만': 4, '십만': 5, '백만': 6, '천만': 7,
  '억': 8, '십억': 9, '백억': 10, '천억': 11, '조': 12,
}

function readableWon(number: string, unit: string): string | null {
  if (!/^[+-]?(?:\d+|\d{1,3}(?:,\d{3})+)(?:\.\d+)?$/u.test(number)) return null
  const [whole, fraction = ''] = number.replace(/^[+-]/u, '').replaceAll(',', '').split('.')
  const scale = 10n ** BigInt(fraction.length)
  const scaled = BigInt(whole + fraction) * (10n ** BigInt(wonUnitPowers[unit]))
  // Preserve exact amounts; never round a fractional won or use floating-point arithmetic.
  if (scaled % scale !== 0n) return null
  let remaining = scaled / scale
  const parts: string[] = []
  for (const [size, label] of [[1_000_000_000_000n, '조'], [100_000_000n, '억'], [10_000n, '만']] as const) {
    const amount = remaining / size
    if (amount > 0n) parts.push(`${amount.toLocaleString('ko-KR')}${label}`)
    remaining %= size
  }
  const sign = /^[+-]/u.exec(number)?.[0] ?? ''
  if (remaining > 0n || parts.length === 0) return sign + [...parts, `${remaining.toLocaleString('ko-KR')}원`].join(' ')
  return sign + parts.join(' ') + ' 원'
}

export function formatSupportScale(value: string): string {
  return value.replace(
    /(?<![\d.,A-Za-z+-])([+-]?\d[\d,]*(?:\.\d+)?)(?:([ \t]*(?:~|∼|–|—|－|-|부터)[ \t]*)([+-]?\d[\d,]*(?:\.\d+)?))?[ \t]*(천억|백억|십억|천만|백만|십만|조|억|만|천|백)?[ \t]*원/gu,
    (match: string, number: string, separator: string | undefined, upper: string | undefined, unit = '', offset: number) => {
      const prefix = value.slice(0, offset)
      // Do not reinterpret part of a compound amount or a list with an omitted shared unit.
      if (/\d[\d,.]*[ \t]*(?:조|억|만|천|백)[ \t]*$/u.test(prefix)) return match
      if (/\d[ \t]*[~∼–—－/·,-][ \t]*$/u.test(prefix)) return match
      const lowerText = readableWon(number, unit)
      const upperText = upper === undefined ? null : readableWon(upper, unit)
      if (lowerText === null || (upper !== undefined && upperText === null)) return match
      return upper === undefined ? lowerText : `${lowerText}${separator}${upperText}`
    },
  )
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

// Strip only standalone table markers; retain meaningful numbers, targets and the original evidence.
export function submissionDocumentName(value: string): string {
  const prefixes = [
    /^[•●○◦▪■□※*①-⑳㉑-㉟]\s*/u,
    /^\d{1,2}[.)](?=\s|[가-힣A-Za-z(])\s*/u,
    /^해당\s*시(?:\s*제출)?(?=\s|[①-⑳㉑-㉟([:：·-]|$)\s*[:：·-]?\s*/u,
    /^(?:필수|선택)(?:\s*제출)?\s*(?:[:：·-]\s*|(?=[①-⑳㉑-㉟([]|\d{1,2}[.)]))/u,
    /^[([]\s*(?:필수|선택|해당\s*시)(?:\s*제출)?\s*[)\]]\s*/u,
    /^[([]\s*(?:증빙\s*서류|제출\s*서류|구비\s*서류)\s*[)\]]\s*/u,
    /^[([]\s*(?:붙임|별첨|첨부|서식|별지|양식)\s*제?\s*\d+(?:\s*[-의]\s*\d+)*\s*호?\s*[)\]]\s*/u,
  ]
  let name = value.trim()
  for (;;) {
    const prefix = prefixes.find(pattern => pattern.test(name))
    if (!prefix) break
    name = name.replace(prefix, '').trim()
  }
  name = name.replace(/\s*[([]\s*(?:필수|선택|해당\s*시)(?:\s*제출)?\s*[)\]]\s*$/u, '').trim()
  return name || value.trim()
}

// Old API responses retain their full guidance in a disclosure; do not guess row boundaries in the UI.
export function documentPresentation(item: SubmissionDocument) {
  const names = item.documentNames?.filter(name => name.trim()) ?? []
  const structured = names.length > 0
  const unavailableCondition = '원문에 ‘해당 시 제출’로 명시되어 있습니다. 구체적인 적용 대상은 원문을 확인해 주세요.'
  const conditions = item.conditions?.filter(condition => condition.trim() && condition.trim() !== unavailableCondition) ?? []
  if (!structured) {
    if (item.appliesTo?.trim()) conditions.push(`제출 대상: ${item.appliesTo.trim()}`)
    if (item.detail?.trim() && item.detail !== item.title && item.detail !== item.appliesTo) conditions.push(item.detail.trim())
  }
  return {
    names: [...new Set((structured ? names : [item.title]).map(submissionDocumentName))],
    conditions: [...new Set(conditions)],
    description: structured && item.detail !== item.title ? item.detail : null,
    formats: [...new Set((item.formats ?? []).map(format => format.trim().toUpperCase()).filter(Boolean))],
  }
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
