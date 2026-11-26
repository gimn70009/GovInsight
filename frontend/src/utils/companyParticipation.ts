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

const requiredSections = new Set(['BASIC', 'ELIGIBILITY', 'TARGETED'])

export function companyConditionSourceKey(source: ParticipationRule['sources'][number]): string {
  return JSON.stringify([source.origin, source.attachmentName, source.sectionTitle, source.location, source.excerpt])
}

function uniqueSources(sources: ParticipationRule['sources']): ParticipationRule['sources'] {
  return [...new Map(sources.filter(source => source.excerpt?.trim())
    .map(source => [companyConditionSourceKey(source), source])).values()]
}

function displayable(entry: ParticipationCriterionEntry): boolean {
  return entry.displayStatus === 'VERIFIED' && Boolean(entry.rule.text.trim())
    && (requiredSections.has(entry.rule.section) || entry.rule.section === 'PREFERENCE' || entry.rule.section === 'EXCLUSION')
}

/** Merge only exactly equal display contracts; interpreting meaning belongs to the server. */
function identicalConditions(entries: ParticipationCriterionEntry[]): ParticipationCriterionEntry[] {
  const groups = new Map<string, ParticipationCriterionEntry>()
  for (const entry of entries) {
    const { rule } = entry
    const key = JSON.stringify([rule.section, rule.text, rule.scope, rule.audience,
      entry.displayStatus, rule.details, rule.exceptions, rule.guidance, rule.outcomes,
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

/** Exclusions form a single notice-level section, independent of the seven criteria. */
export function companyParticipationExclusions(guide: ParticipationGuide | null | undefined) {
  const entries = guide?.categories?.flatMap(category => category.rows.flatMap(row => row.entries)) ?? []
  return identicalConditions(entries.filter(entry => displayable(entry) && entry.rule.section === 'EXCLUSION'))
}

/** Administrative setup is displayed separately from applicant qualifications. */
export function companyParticipationGuidance(guide: ParticipationGuide | null | undefined) {
  const entries = guide?.categories?.flatMap(category => category.rows.flatMap(row => row.entries)) ?? []
  return identicalConditions(entries.filter(entry => entry.rule.section === 'APPLICATION_GUIDANCE'
    && entry.displayStatus === 'VERIFIED'
    && Boolean(entry.rule.text.trim())))
}

// Old cached outputs sometimes contain the prompt's field descriptions instead of values.
function contextLabel(value: string | null | undefined): string | null {
  const label = value?.trim()
  if (!label) return null
  const compact = label.replace(/\s+/g, '')
  if (/^(?:scope|audience|parent|section|program|null|상위구역|원문구역|적용유형|신청자격|신청요건|신청자격및신청요건|지원대상|지원내용|사업개요|신청역할|신청주체명|유형명|유형미지정|미확인|확인필요|적용범위확인필요)$/i.test(compact)
    || /^\[(?:상위구역|원문구역|적용유형)\]/.test(compact)
    || /^실제사업[·ㆍ]과제유형(?:\(.*\))?$/.test(compact)) return null
  return label
}

function scopeReviewRequested(scope: string | null | undefined): boolean {
  return (scope ?? '').split(' / ').flatMap(path => path.split(' · ')).some(part =>
    /^(?:미확인|확인필요|적용범위확인필요)$/.test(part.replace(/\s+/g, '')))
}

function businessScopeLabel(value: string | null | undefined): string | null {
  const paths = (value ?? '').split(' / ').map(path => path.split(' · ').map(part => {
    const label = part.trim().toLowerCase() === 'common' ? '공통' : contextLabel(part)
    if (!label) return null
    const heading = label.replace(/\s+/g, '')
      .replace(/^(?:\(?(?:[0-9]+(?:-[0-9]+)*|[가-힣]|[IVXⅠ-Ⅻ]+)[.)]|[①-⑳])/, '')
    if (!contextLabel(heading)
      || /^(?:(?:(?:세부|내역)?(?:사업|과제))?(?:지원)?(?:유형|구분)|(?:사업|유형)별(?:지원계획|지원내용|신청자격)|신청자격및선정기준)$/.test(heading)) return null
    return label
  }).filter(Boolean).join(' · ')).filter(Boolean)
  return [...new Set(paths)].join(' / ') || null
}

export function companyConditionScope(entry: ParticipationCriterionEntry): string | null {
  return businessScopeLabel(entry.rule.scope)
}

export function companyConditionPresentation(entry: ParticipationCriterionEntry) {
  const scope = companyConditionScope(entry)
  return {
    scope,
    // A missing narrower scope is not uncertainty; only an explicit marker requests review.
    scopeReviewRequired: scopeReviewRequested(entry.rule.scope),
    audience: contextLabel(entry.rule.audience),
    reviewRequired: entry.displayStatus === 'REVIEW_REQUIRED',
    sentence: entry.displayStatus === 'REVIEW_REQUIRED'
      ? `${entry.rule.section === 'APPLICATION_GUIDANCE' ? '접수 준비 사항' : '신청 요건'}의 정확한 내용은 원문 근거를 확인해 주세요.`
      : entry.rule.text,
    notices: entry.rule.guidance ?? [],
    notes: [...new Set([...(entry.rule.details ?? []), ...(entry.rule.exceptions ?? [])])]
      .filter(note => note.trim()),
  }
}

/** Explicit user filter; common or unspecified scopes remain visible without another API call. */
export function companyConditionAppliesTo(entry: ParticipationCriterionEntry, selected: string): boolean {
  if (!selected || scopeReviewRequested(entry.rule.scope)) return true
  const scope = companyConditionScope(entry)
  if (!scope || scope === '공통') return true
  return scope.split(' / ').some(value => value === '공통' || value === selected
    || (value.endsWith(' · 공통') && selected.startsWith(value.slice(0, -3))))
}

export function activeCompanyConditionProgram(
  selection: { detectionId: number; program: string }, detectionId: number, programs: string[], total: number,
): string {
  return programs.length >= 2 && total >= 8 && selection.detectionId === detectionId
    && programs.includes(selection.program) ? selection.program : ''
}
