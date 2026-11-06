import type { ParticipationCriterionEntry, ParticipationGuide, ParticipationRule } from '../api/types'

export const companyFields = [
  { code: 'BUSINESS_AGE', label: '업력' },
  { code: 'REVENUE', label: '매출·수출' },
  { code: 'IP', label: '특허·인증·지정' },
  { code: 'SECTOR', label: '업종·품목·서비스' },
  { code: 'BUSINESS_FORM', label: '신청 주체' },
  { code: 'EXPERIENCE', label: '실적·수행 역량' },
  { code: 'CREDIT', label: '신용·재무 요건' },
] as const

const requiredSections = new Set(['BASIC', 'ELIGIBILITY', 'TARGETED', 'EXCLUSION'])

export function companyConditionSourceKey(source: ParticipationRule['sources'][number]): string {
  return JSON.stringify([source.origin, source.attachmentName, source.sectionTitle, source.location, source.excerpt])
}

function uniqueSources(sources: ParticipationRule['sources']): ParticipationRule['sources'] {
  return [...new Map(sources.filter(source => source.excerpt?.trim())
    .map(source => [companyConditionSourceKey(source), source])).values()]
}

function displayable(entry: ParticipationCriterionEntry): boolean {
  return entry.displayStatus === 'VERIFIED' && Boolean(entry.rule.text.trim())
    && (requiredSections.has(entry.rule.section) || entry.rule.section === 'PREFERENCE')
}

/** Merge only exactly equal display contracts; interpreting meaning belongs to the server. */
function identicalConditions(entries: ParticipationCriterionEntry[]): ParticipationCriterionEntry[] {
  const groups = new Map<string, ParticipationCriterionEntry>()
  for (const entry of entries) {
    const { rule } = entry
    const key = JSON.stringify([rule.section, rule.text, rule.scope, rule.audience,
      rule.details, rule.exceptions, rule.guidance, rule.outcomes,
      entry.context, entry.relation, entry.relatedCriteria])
    const previous = groups.get(key)
    groups.set(key, previous ? {
      ...previous,
      supportingQuotes: [...new Set([...(previous.supportingQuotes ?? []), ...(entry.supportingQuotes ?? [])])],
      rule: { ...previous.rule, sources: uniqueSources([...previous.rule.sources, ...rule.sources]) },
    } : entry)
  }
  return [...groups.values()]
}

/** Trust server verification, classification and wording. Do not revalidate or rewrite meaning. */
export function companyParticipationRows(guide: ParticipationGuide | null | undefined) {
  const supplied = guide?.categories?.flatMap(category => category.rows) ?? []
  return companyFields.map(field => {
    const entries = supplied.filter(row => row.code === field.code).flatMap(row => row.entries)
    const visible = identicalConditions(entries.filter(displayable))
    return {
      ...field,
      mandatory: visible.filter(entry => requiredSections.has(entry.rule.section)),
      preference: visible.filter(entry => entry.rule.section === 'PREFERENCE'),
    }
  })
}

/** Empty context is unspecified. Preserve all nonempty server-supplied labels verbatim. */
export function companyConditionScope(entry: ParticipationCriterionEntry): string | null {
  return entry.rule.scope?.trim() ? entry.rule.scope : null
}

export function companyConditionPresentation(entry: ParticipationCriterionEntry) {
  return {
    scope: companyConditionScope(entry),
    audience: entry.rule.audience?.trim() ? entry.rule.audience : null,
    sentence: entry.rule.text,
    notes: [...new Set([...(entry.rule.details ?? []), ...(entry.rule.exceptions ?? [])])]
      .filter(note => note.trim()),
  }
}

/** Explicit user filter; common or unspecified scopes remain visible without another API call. */
export function companyConditionAppliesTo(entry: ParticipationCriterionEntry, selected: string): boolean {
  if (!selected) return true
  const scope = companyConditionScope(entry)
  if (!scope || scope === '공통') return true
  return scope.split(' / ').some(value => value === '공통' || value === selected
    || (value.endsWith(' · 공통') && selected.startsWith(value.slice(0, -3))))
}

export function activeCompanyConditionProgram(
  selection: { detectionId: number; program: string }, detectionId: number, programs: string[], total: number,
): string {
  return programs.length >= 3 && total >= 8 && selection.detectionId === detectionId
    && programs.includes(selection.program) ? selection.program : ''
}
