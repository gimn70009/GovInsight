import type { DocumentAnalysis, DocumentAttachment, ProposalPreparationItem, ResponsibleOrganization, SubmissionDocument, SubmissionGuide } from '../api/types'

// Only whole-field placeholders are missing. Partial conditions and amounts stay intact.
export function summaryValue(value: string | null | undefined): string | null {
  const text = value?.trim()
  if (!text || /^(?:미확인|확인 필요|원문 확인 필요|정보 없음|원문에서 확인하지 못했습니다)[.!]?$/u.test(text)) return null
  return text
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
