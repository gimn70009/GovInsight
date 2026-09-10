import type { DocumentAnalysis } from '../api/types'

// The date field is authoritative. Free-form summaries never determine closure.
export function isApplicationExpired(analysis: DocumentAnalysis | null, now = Date.now()): boolean {
  if (!analysis) return false
  const saved = analysis.applicationDeadline?.trim() || analysis.proposal.preparation?.applicationDeadline?.trim()
  const urgency = analysis.opportunity?.dimensions.find(item => item.type === 'URGENCY')?.reason ?? ''
  const legacy = urgency.match(/신청 마감일은\s*(.+?)(?:이며|입니다|까지)/u)?.[1]
  const deadline = parseApplicationDeadline(saved || legacy || '')
  return deadline !== null && now >= deadline
}

export function parseApplicationDeadline(text: string): number | null {
  const pattern = /(20\d{2})\s*(?:[-./]|년)\s*(\d{1,2})\s*(?:[-./]|월)\s*(\d{1,2})(?!\d)(?:\s*일|\.)?(?:\s*\([^)]*\))?(?:[T\s]+(\d{1,2})\s*(?::|시)\s*(\d{2})?\s*분?)?/gu
  const dates = [...text.matchAll(pattern)]
  const range = /[~∼～]|부터/u.test(text)
  if (!dates.length || dates.length > 2 || (dates.length === 2 && !range)) return null
  if (range && dates.length === 1) return null // A yearless range end is ambiguous.
  const match = dates.at(-1)!
  const [year, month, day] = match.slice(1, 4).map(Number)
  const check = new Date(Date.UTC(year, month - 1, day))
  if (check.getUTCFullYear() !== year || check.getUTCMonth() !== month - 1 || check.getUTCDate() !== day) return null
  if (match[4] === undefined) return Date.UTC(year, month - 1, day + 1) - 9 * 3600_000
  const hour = Number(match[4]), minute = Number(match[5] ?? 0)
  if (hour > 23 || minute > 59) return null
  return Date.UTC(year, month - 1, day, hour, minute) - 9 * 3600_000
}

export function normalizeLegalNarrative(text: string): string {
  let value = text.trim()
  const endings: Array<[string, string]> = [
    ['아니다', '아닙니다'], ['있다', '있습니다'], ['없다', '없습니다'],
    ['한다', '합니다'], ['된다', '됩니다'], ['하다', '합니다'], ['이다', '입니다'],
    ['다르다', '다릅니다'], ['따른다', '따릅니다'], ['했다', '했습니다'],
  ]
  for (const [plain, polite] of endings) {
    value = value.replace(new RegExp(`${plain}(?=[.!?](?:\\s|$)|$)`, 'gu'), polite)
  }
  value = value.replace(/여부 확인(?=\.?$)/u, '여부를 확인할 필요가 있습니다')
    .replace(/확인 필요(?=\.?$)/u, '확인이 필요합니다')
    .replace(/확인(?=\.?$)/u, '확인이 필요합니다')
  if (/[가-힣]니다$/u.test(value)) value += '.'
  return value
}
