import type { ParticipationCriterionEntry, ParticipationGuide } from '../api/types'

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
const normalized = (text: string) => text.replace(/\s+/gu, '')

/** Display supplied original conditions only; never infer a condition or preference in the browser. */
export function companyParticipationRows(guide: ParticipationGuide | null | undefined) {
  const supplied = guide?.categories?.flatMap(category => category.rows) ?? []
  return companyFields.map(field => {
    const entries = supplied.filter(row => row.code === field.code).flatMap(row => row.entries)
    const verified = entries.filter(entry => {
      const quotes = entry.supportingQuotes?.length ? entry.supportingQuotes : [entry.rule.text]
      return entry.displayStatus === 'VERIFIED' && entry.rule.text.trim()
        && quotes.every(quote => quote.trim() && entry.rule.sources.some(source =>
          normalized(source.excerpt ?? '').includes(normalized(quote))))
        && !unrelatedCondition(formatCompanyCondition(entry.rule.text).lead, field.code)
        && entry.rule.sources.some(source => !documentSection(source.sectionTitle ?? ''))
    })
    const displayable = verified.filter(entry => formatCompanyCondition(entry.rule.text).lead.trim())
    const visible = field.code === 'SECTOR'
      ? displayable.filter(entry => isSpecificIndustryCondition(entry.rule.text)) : displayable
    const groups = readableConditions(visible, field.code)
    const mandatory = groups.map(group => group.entry).filter(entry => requiredSections.has(entry.rule.section))
    const preference = groups.map(group => group.entry).filter(entry => entry.rule.section === 'PREFERENCE')
    const shown = new Set(groups.filter(group => requiredSections.has(group.entry.rule.section)
      || group.entry.rule.section === 'PREFERENCE').flatMap(group => group.originals))
    const unresolved = entries.filter(entry => !shown.has(entry)
      && entry.rule.section !== 'DEDUCTION' && entry.rule.section !== 'FUNDING')
    const first = unresolved[0]
    const evidence: ParticipationCriterionEntry[] = first ? [{
      ...first, displayStatus: 'EVIDENCE_ONLY', rule: {
        ...first.rule, label: field.label, text: '',
        sources: unresolved.flatMap(entry => entry.rule.sources).filter(source => source.excerpt?.trim()),
      },
    }] : []
    return { ...field, mandatory, preference, evidence }
  })
}

export function companyConditionEmptyText(status: ParticipationGuide['rewriteStatus'], unresolved: boolean): string {
  if (status === 'PENDING') return '조건 확인 중'
  if (status !== 'READY' || unresolved) return '확인 필요'
  return '확인된 조건 없음'
}

const genericScopeHeading = /^(?:신청\s*(?:자격|조건|요건|대상|방법)|지원\s*(?:자격|조건|요건|대상|내용|계획|유형)?|참여\s*(?:자격|조건|요건|대상)|사업\s*(?:개요|목적|내용)|공통\s*사항)$/u
const scopeOrdinal = /^[①-⑳㉑-㉟㊱-㊿]\s*/u

/** Scope is a supplied program/common label; ordinary section headings belong to source evidence. */
export function companyConditionScope(entry: ParticipationCriterionEntry): string | null {
  const parts = entry.rule.scope?.split(/ \/ /u).map(part => {
    const labels = part.split(/ · /u).map(label => label.trim().replace(scopeOrdinal, '').trim())
    const meaningful = labels.filter(label => label && !genericScopeHeading.test(label)
      && !/^(?:[（(□■◦○ㅇ*※-]|20[0-9]{2}년)/u.test(label) && label.length <= 60)
    // '공통 · 지원' may be a damaged section path, not proof that every program shares a condition.
    if (meaningful.length === 1 && meaningful[0] === '공통' && labels.length !== 1) return null
    return meaningful.length ? meaningful.join(' · ') : null
  })
  // Do not silently drop an unknown alternative from an otherwise known set of programs.
  return parts && parts.every(Boolean) ? parts.join(' / ').trim() || null : null
}

/** Explicit user filter; common or unspecified scopes stay visible for every selected program. */
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

function conditionAudience(entry: ParticipationCriterionEntry): string | null {
  return entry.rule.audience?.trim().replace(scopeOrdinal, '').trim() || null
}

function audienceSentence(text: string, audience: string): string {
  const escape = audience.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
  const labelled = new RegExp(`^\\s*(?:[-•]\\s*)?[（(]\\s*${escape}\\s*[）)]\\s*`, 'u')
  const body = text.replace(labelled, '')
  // A role mentioned as an object or a registration requirement does not identify the sentence subject.
  const subject = new RegExp(`^(?:(?:국내|국외|중소|중견|대|중소[·ㆍ․‧]중견)\\s+)*${escape}(?:은|는|이|가|의\\s*(?:조건|요건|자격))(?:\\s|$)`, 'u')
  if (body === text && subject.test(text)) return text
  const last = audience.charCodeAt(audience.length - 1)
  const particle = last >= 0xac00 && last <= 0xd7a3 && (last - 0xac00) % 28 === 0 ? '는' : '은'
  return `${audience}${particle} ${body}`
}

export function scopedCompanyConditionSentence(entry: ParticipationCriterionEntry, code: string): string {
  const restored = restoreConditionScope(entry)
  if (!restored) return ''
  const text = companyConditionSentence(restored.rule.text, restored.rule.section === 'PREFERENCE', code)
  if (!text) return ''
  const scope = companyConditionScope(restored)
  const audience = conditionAudience(restored)
  const sentence = audience ? audienceSentence(text, audience) : text
  if (!scope || text.startsWith(scope)) return sentence
  if (scope === '공통') return `공통으로 ${sentence}`
  const label = scope.replace(/ · 공통$/u, '')
  if (audience) return `${label} 유형${sentence.startsWith(audience) ? '의' : '에서'} ${sentence}`
  return `${label} 유형${entry.rule.section === 'PREFERENCE' ? '에서는' : '은'} ${text}`
}


/** Split supplied context from the complete sentence; never edit context out of original text. */
export function companyConditionPresentation(entry: ParticipationCriterionEntry, code: string) {
  const restored = restoreConditionScope(entry)
  if (!restored) return { scope: null, audience: null, sentence: '', notes: [] as string[] }
  const text = companyConditionSentence(restored.rule.text, restored.rule.section === 'PREFERENCE', code)
  const audience = conditionAudience(restored)
  return {
    scope: companyConditionScope(restored),
    audience,
    sentence: text && audience ? audienceSentence(text, audience) : text,
    notes: [...new Map([
      ...formatCompanyCondition(restored.rule.text).notes,
      ...(restored.rule.details ?? []), ...(restored.rule.exceptions ?? []),
    ].map(note => cleanConditionLine(note.replace(/^(?:※|[*＊]+)\s*/u, '')))
      .filter(Boolean).map(note => [normalized(note), note])).values()],
  }
}


const documentWords = /(?:신청|제출|구비)\s*서류|체크\s*리스트|확약서|납세증명|재무제표증명[^\n]*제출|사업\s*신청서|사업계획서|견적서/u

function documentSection(heading: string): boolean {
  return documentWords.test(heading.split('[원문 구역]').at(-1) ?? '')
}

function unrelatedCondition(text: string, code: string): boolean {
  return vagueCondition(text) || (code === 'CREDIT' && !concreteCredit(text)) || (code !== 'BUSINESS_FORM' && /상시\s*근로자|근로자\s*수/u.test(text)) || documentWords.test(text) || /\[\/?표\]|소재지|대표자|국세|지방세|체납|휴[·ㆍ‧.]?폐업/u.test(text)
}


// These are descriptions of possible checks, not actual eligibility thresholds.
function vagueCondition(text: string): boolean {
  return /(?:포함|요구|적용|필요)(?:될|할|일|될수|할수)?\s*수\s*(?:있|있다)|(?:요건|조건)\s*충족\s*여부/u.test(text)
}

function concreteCredit(text: string): boolean {
  return /(?:신용|평가)\s*(?:평가)?\s*등급.{0,24}(?:[A-D]{1,3}[+-]?|\d)|부채\s*비율.{0,20}\d|자본\s*잠식/u.test(text)
}

// Never guess Korean word boundaries. Recover them only from matching supplied evidence.
function brokenSpacing(text: string): boolean {
  return /[가-힣0-9]{35,}/u.test(text)
}

function conditionIdentity(text: string): string {
  const display = formatCompanyCondition(text)
  return normalized([display.lead, ...display.notes].join(' '))
    .replace(/^(?:(?:공통사항)|(?:신청자격은|신청요건은|지원대상은|신청대상은))+/u, '')
}

function conditionKeys(entry: ParticipationCriterionEntry): string[] {
  return (entry.supportingQuotes?.length ? entry.supportingQuotes : [entry.rule.text])
    .map(conditionIdentity).filter(Boolean)
}

function sameCondition(left: ParticipationCriterionEntry, right: ParticipationCriterionEntry): boolean {
  return left.rule.section === right.rule.section && left.rule.audience === right.rule.audience
    && JSON.stringify(conditionKeys(left).sort()) === JSON.stringify(conditionKeys(right).sort())
}

function restoreSpacing(entry: ParticipationCriterionEntry): ParticipationCriterionEntry | null {
  const lead = formatCompanyCondition(entry.rule.text).lead
  if (!brokenSpacing(lead)) return entry
  const fragment = lead.replace(/이어야\s*합니다\.$/u, '')
  const needle = normalized(fragment)
  for (const source of entry.rule.sources) {
    const original = formatCompanyCondition(source.excerpt ?? '').lead
    const positions: number[] = []
    let compact = ''
    for (let index = 0; index < original.length; index++) {
      if (!/\s/u.test(original[index])) { compact += original[index]; positions.push(index) }
    }
    const start = compact.indexOf(needle)
    if (start < 0) continue
    const restored = original.slice(positions[start], positions[start + needle.length - 1] + 1)
    if (brokenSpacing(restored)) continue
    return { ...entry, rule: { ...entry.rule, text: restored } }
  }
  return null
}

// A dangling particle is not a program name. Never turn a damaged scope into a common rule.
function fragmentedScope(scope: string): boolean {
  return /^(?:는|은|을|를|의|에서|으로)(?:\s|사업|기업|기관|단체)/u.test(scope.trim())
}

function restoreConditionScope(entry: ParticipationCriterionEntry): ParticipationCriterionEntry | null {
  const scope = companyConditionScope(entry)
  if (!scope) return entry.rule.scope?.trim() ? null : entry
  if (!fragmentedScope(scope)) return scope === entry.rule.scope ? entry : { ...entry, rule: { ...entry.rule, scope } }
  const candidates = new Set(entry.rule.sources.flatMap(source => {
    const heading = (source.sectionTitle ?? '').split('[원문 구역]').at(-1)?.trim() ?? ''
    const labelled = /^(?:사업\s*유형|유형|프로그램|트랙)\s*[:：]\s*(.+)$/u.exec(heading)?.[1].trim()
    return labelled && labelled.length <= 60 && !fragmentedScope(labelled)
      && normalized(labelled).endsWith(normalized(scope)) ? [labelled] : []
  }))
  if (candidates.size !== 1) return null
  return { ...entry, rule: { ...entry.rule, scope: [...candidates][0] } }
}

// Compare the actual supplied clause, allowing only equivalent sentence endings.
function conditionStem(text: string): string {
  return conditionIdentity(text)
    .replace(/^(?:공통으로|공통사항|신청자격은|신청요건은|지원대상은|신청대상은)/u, '')
    .replace(/(?:이|가)?(?:지원|신청|참여)?대상입니다[.!]?$/u, '')
    .replace(/(?:이어야합니다|여야합니다|해야합니다|입니다)[.!]?$/u, '')
    .replace(/[.!]$/u, '')
}

function conditionFacts(text: string): string {
  return JSON.stringify(normalized(text).match(/\d+(?:[,.]\d+)*|이상|이하|미만|초과|이내|이전|이후|공고일|접수마감일|신청일|또는|혹은|및|그리고|이면서|동시에|제외|예외|포함|불가|미해당|아닌|아니한|단[,，]|다만|등록|출원/gu) ?? [])
}

function sameConditionContext(left: ParticipationCriterionEntry, right: ParticipationCriterionEntry): boolean {
  const context = (entry: ParticipationCriterionEntry) => JSON.stringify([
    requiredSections.has(entry.rule.section) && entry.rule.section !== 'EXCLUSION' ? 'REQUIRED' : entry.rule.section,
    entry.rule.audience, entry.relation,
    [...(entry.relatedCriteria ?? [])].sort(),
    ...[entry.rule.details, entry.rule.exceptions, entry.rule.guidance].map(values => values.map(normalized).sort()),
    entry.rule.outcomes,
    formatCompanyCondition(entry.rule.text).notes.map(normalized),
  ])
  return context(left) === context(right)
}

function sameClause(left: ParticipationCriterionEntry, right: ParticipationCriterionEntry): boolean {
  return sameConditionContext(left, right) && conditionStem(left.rule.text) === conditionStem(right.rule.text)
    && JSON.stringify(conditionKeys(left).map(conditionStem).sort())
      === JSON.stringify(conditionKeys(right).map(conditionStem).sort())
}

function extendsClause(detailed: string, short: string): boolean {
  return detailed.startsWith(short) && detailed.length > short.length
    && /^(?:중|인(?:기업|기관|단체)|으로서|로서)/u.test(detailed.slice(short.length))
}

function moreDetailedCondition(detailed: ParticipationCriterionEntry, short: ParticipationCriterionEntry): boolean {
  if (!sameConditionContext(detailed, short)
    || conditionFacts(detailed.rule.text) !== conditionFacts(short.rule.text)
    || !extendsClause(conditionStem(detailed.rule.text), conditionStem(short.rule.text))) return false
  const detailedQuotes = conditionKeys(detailed).map(conditionStem)
  return conditionKeys(short).map(conditionStem).every(quote => detailedQuotes.some(other =>
    other === quote || extendsClause(other, quote)))
}

// Identical complete display contracts can share distinct originals (PDF/HWPX layout differences).
// Preserve every quotation and keep different qualifiers or conflicting quoted numeric facts apart.
function sameCompleteDisplay(left: ParticipationCriterionEntry, right: ParticipationCriterionEntry): boolean {
  if (![left, right].every(entry => /(?:다|요)\.$/u.test(entry.rule.text.trim()))) return false
  const contract = (entry: ParticipationCriterionEntry) => JSON.stringify([
    entry.rule.section, normalized(entry.rule.scope ?? ''), normalized(entry.rule.audience ?? ''),
    normalized(entry.rule.text), entry.context, entry.relation, entry.relatedCriteria,
    ...[entry.rule.details, entry.rule.exceptions, entry.rule.guidance].map(values => values.map(normalized)),
    entry.rule.outcomes,
    (entry.supportingQuotes?.length ? entry.supportingQuotes : [entry.rule.text]).map(conditionFacts).sort(),
  ])
  return contract(left) === contract(right)
}

function readableConditions(entries: ParticipationCriterionEntry[], code: string) {
  const candidates = entries.flatMap(original => {
    const restored = restoreConditionScope(original)
    const entry = restored && (restoreSpacing(restored) ?? restored)
    return entry ? [{ entry, original }] : []
  })
  const equivalent = (left: ParticipationCriterionEntry, right: ParticipationCriterionEntry) =>
    sameCompleteDisplay(left, right)
      || (sameConditionContext(left, right) && (sameCondition(left, right) || sameClause(left, right)))
  const related = (left: ParticipationCriterionEntry, right: ParticipationCriterionEntry) =>
    equivalent(left, right) || moreDetailedCondition(left, right) || moreDetailedCondition(right, left)
  const sameScopedStatement = (left: ParticipationCriterionEntry, right: ParticipationCriterionEntry) =>
    sameConditionContext(left, right) && conditionIdentity(left.rule.text) === conditionIdentity(right.rule.text)
      && JSON.stringify(conditionKeys(left).sort()) === JSON.stringify(conditionKeys(right).sort())
  const scopeParts = (entry: ParticipationCriterionEntry) => entry.rule.scope && !entry.rule.scope.includes(' / ')
    ? entry.rule.scope.split(' · ').map(normalized) : null
  const suffix = (full: string[], partial: string[]) => full.length > partial.length
    && partial.every((part, index) => part === full[full.length - partial.length + index])
  const suppliedScope = (entry: ParticipationCriterionEntry): string | null => {
    const scope = entry.rule.scope
    if (!scope) return null
    const parts = scopeParts(entry)
    if (!parts) return normalized(scope)
    const parents = candidates.filter(other => sameScopedStatement(entry, other.entry))
      .map(other => scopeParts(other.entry)).filter((value): value is string[] => Boolean(value && suffix(value, parts)))
    const maximal = parents.filter(parent => !parents.some(other => suffix(other, parent)))
    const unique = new Set(maximal.map(parent => parent.join('·')))
    return unique.size === 1 ? [...unique][0] : normalized(scope)
  }
  // A partial hierarchy can join only one exact full path for the same statement and evidence.
  // Unspecified scope keeps the existing uniquely matching supplied-scope rule.
  const effectiveScope = (entry: ParticipationCriterionEntry) => {
    const supplied = suppliedScope(entry)
    if (supplied) return supplied
    const scopes = new Set(candidates.filter(other => related(entry, other.entry))
      .map(other => suppliedScope(other.entry)).filter(Boolean))
    return scopes.size === 1 ? [...scopes][0] : null
  }
  type Group = { entry: ParticipationCriterionEntry; originals: ParticipationCriterionEntry[] }
  const groups: Group[] = []
  const displayable = (entry: ParticipationCriterionEntry) => Boolean(
    companyConditionSentence(entry.rule.text, entry.rule.section === 'PREFERENCE', code))
  const preferred = (left: ParticipationCriterionEntry, right: ParticipationCriterionEntry) => {
    if (displayable(left) !== displayable(right)) return displayable(left) ? left : right
    if (moreDetailedCondition(left, right)) return left
    if (moreDetailedCondition(right, left)) return right
    const score = (value: ParticipationCriterionEntry) =>
      (brokenSpacing(value.rule.text) ? 10 : 0) + (/(?:다|요)\.$/u.test(value.rule.text) ? 0 : 1)
    return score(left) !== score(right) ? (score(left) < score(right) ? left : right)
      : left.rule.text.localeCompare(right.rule.text) <= 0 ? left : right
  }
  for (const { entry, original } of candidates) {
    const group = groups.find(group => equivalent(group.entry, entry)
      && effectiveScope(group.entry) === effectiveScope(entry))
    if (!group) { groups.push({ entry, originals: [original] }); continue }
    group.originals.push(original)
    group.entry = preferred(group.entry, entry)
  }
  const covers = (detailed: Group, short: Group) => displayable(detailed.entry)
    && effectiveScope(detailed.entry) === effectiveScope(short.entry)
    && moreDetailedCondition(detailed.entry, short.entry)
  const destinations = groups.map(group => {
    const richer = groups.filter(other => other !== group && covers(other, group))
    const maximal = richer.filter(other => !richer.some(candidate => candidate !== other && covers(candidate, other)))
    // A shared summary must not be assigned arbitrarily to one of multiple different detailed conditions.
    return maximal.length === 1 ? maximal[0] : group
  })
  return groups.flatMap((group, index) => {
    if (destinations[index] !== group
      || !companyConditionSentence(group.entry.rule.text, group.entry.rule.section === 'PREFERENCE', code)) return []
    const originals = groups.flatMap((other, position) => destinations[position] === group ? other.originals : [])
    if (originals.length === 1) return [{ entry: group.entry, originals }]
    return [{ originals, entry: { ...group.entry,
      supportingQuotes: [...new Set(originals.flatMap(value => value.supportingQuotes?.length ? value.supportingQuotes : [value.rule.text]))],
      rule: { ...group.entry.rule, scope: candidates.find(value =>
        value.entry.rule.scope && normalized(value.entry.rule.scope) === effectiveScope(group.entry))?.entry.rule.scope ?? group.entry.rule.scope,
        sources: originals.flatMap(value => value.rule.sources) },
    } }]
  })
}

const damagedDisplayCharacters = /[\uFFFD\p{Co}]/u
const repeatedDisplayUnit = /\d[\d,.]*\s*(년|개월|억원|만원|천원|원|달러|명|개사|개|건|%|점|종)\s+\1(?=\s|$|[.,·])/u

/** Same bounded display-quality contract as the server; never guess replacement words. */
export function isReadableCompanyCondition(text: string): boolean {
  const value = text.trim()
  return Boolean(value) && !damagedDisplayCharacters.test(value) && !repeatedDisplayUnit.test(value)
    && !/^(?:및|또는|혹은|와|과|은|는|을|를|의)(?:\s|[:：])/u.test(value)
    && !/[□■]|(?:신청\s*자격|지원\s*대상|신청\s*방법)\s*[:：]/u.test(value)
    && !/^[◦○●]\s+/u.test(value)
    && !/(?<![가-힣])(?:의|은|는|을|를|및|또는|중|단,?|하는|해당하는|중인|있는|없는|보유한)\s*(?:이어야|여야|입니다|합니다)/u.test(value)
}

/** Convert only recognizable qualification clauses; never wrap an arbitrary source block. */
export function companyConditionSentence(text: string, preference: boolean, code?: string): string {
  if (damagedDisplayCharacters.test(text) || repeatedDisplayUnit.test(text)) return ''
  if (text.split('\n').some(line => /[□■]/u.test(line.replace(/^[\s*□■]+/u, '')))) return ''
  let lead = formatCompanyCondition(text).lead
    .replace(/\(?\s*(?:\[참고\s*\d+\]\s*[,、]?\s*)+(?:참조)?\s*\)?/gu, '').trim()
  // Old cached blocks may contain eligibility, application dates and methods together.
  // Select a labelled qualification line only; keep the full block in the evidence panel.
  if (/(?:신청|접수)\s*(?:기간|방법)|상시\s*모집/u.test(lead)) {
    const lines = lead.split('\n')
    const qualifications = lines.map(line => /^\s*(?:신청|지원)\s*(?:자격|대상)\s*[:：]\s*(.+)$/u.exec(line))
      .filter((match): match is RegExpExecArray => Boolean(match))
    if (qualifications.length !== 1) return ''
    lead = qualifications[0][1].trim()
    if (/(?:신청|접수)\s*(?:기간|방법)/u.test(lead)) return ''
  }
  lead = lead.replace(/^\s*(?:[가-하][.)]\s*)?(?:신청|지원)\s*대상\s*[:：]?\s*(?=\n)/u, '').trim()
  if (!isReadableCompanyCondition(lead) || brokenSpacing(lead) || vagueCondition(lead)) return ''
  // This exact conjunction permits a field-specific sentence; do not split alternatives/exceptions.
  const common = /^(?:신청 자격은 )?(?:(중소기업기본법 제 ?2조에 따라) )?공고일 기준 업력 (\d+년 (?:이상|이내))의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업(?:이어야 합니다\.)?$/u.exec(lead)
  if (common && code === 'BUSINESS_AGE') return `공고일 기준 업력이 ${common[2]}${common[2].endsWith('이내') ? '여야' : '이어야'} 합니다.`
  // Preserve OR lists explicitly instead of turning a list into a mandatory conjunction.
  const alternatives = /^다음 중 하나(?:를)? 충족\n(.+)$/su.exec(lead)
  if (alternatives) {
    const clauses = alternatives[1].split('\n').map(line => line.replace(/^(?:\d+[.)]|[가-하][.)])\s*/u, ''))
    if (clauses.length < 2 || clauses.some(line => !/(?:미만|이상|이하|초과|이내)$/u.test(line))) return ''
    return `${clauses.join(' 또는 ')} 조건 중 하나를 충족해야 합니다.`
  }
  lead = lead.replace(/\s*\n\s*/gu, ' ')
    .replace(/이어야 합니다\. (?:단|다만), /u, '이어야 하지만, ')
  if (/(?:다|요)[.!?]$/u.test(lead)) return lead
  if (/(?:이상|미만|이하|초과|이내|보유)\s*\(단,[^()]+\)$/u.test(lead)) return lead + ' 조건을 충족해야 합니다.'
  if (/(?:이어야|하여야|해야)\s*함$/u.test(lead)) return lead.replace(/함$/u, '합니다.')
  if (/(?:이어야|하여야|해야)$/u.test(lead)) return lead + ' 합니다.'
  if (/무관$/u.test(lead)) return lead.replace(/무관$/u, '제한이 없습니다.')
  const qualifiedEntity = /^(.*(?:기업|기관|단체))\s*(\(.*\))$/u.exec(lead)
  let balance = 0
  const balancedQualifier = qualifiedEntity && [...qualifiedEntity[2]].every(char => {
    balance += char === '(' ? 1 : char === ')' ? -1 : 0
    return balance >= 0
  }) && balance === 0
  if (/(?:기업|기관|단체)$/u.test(lead) || balancedQualifier) return lead + (preference ? '을 우대합니다.' : '이어야 합니다.')
  if (/(?:영위|보유)$/u.test(lead)) return lead + (preference ? ' 시 우대합니다.' : '해야 합니다.')
  if (/(?:이상|미만)$/u.test(lead)) return lead + '이어야 합니다.'
  if (/(?:이하|초과|이내)$/u.test(lead)) return lead + '여야 합니다.'
  if (/우대$/u.test(lead)) return lead + '합니다.'
  if (preference && /가점 \d+점$/u.test(lead)) return lead + '을 부여합니다.'
  return ''
}

/** Hide generic checklists, while retaining actual industry requirements and their exceptions. */
export function isSpecificIndustryCondition(text: string): boolean {
  const value = text.replace(/[가-힣A-Za-z]{2,}(?:진흥원|공단|공사|재단|협회|대학교|연구원|기술원)/gu, '')
  const checklist = /요건\s*검토|신청서.{0,24}제출서류|부적절\s*업종|제외\s*대상|지원\s*제외|체납|휴[·ㆍ.]?\s*폐업|채무\s*불이행/u
  if (!checklist.test(value)) return true
  const industry = /제조|바이오|반도체|정보통신|인공지능|소프트웨어|이차전지|모빌리티|로봇|소부장|KSIC|한국표준산업분류|(?<![A-Za-z])(?:AI|ICT)(?![A-Za-z])|[A-S]\s*\d{2}(?:\s*[~～\-–]\s*[A-S]?\s*\d{2})?|(?:금융|보험|농|어|건설|도매|소매|운수|숙박|음식점|서비스|유흥주점|유흥|도박|사행성)업|(?:관련|유관)\s*(?:산업|분야).{0,30}(?:경험|실적|기술|제품|납품)|소재\s*[·ㆍ,/]\s*부품/iu
  return industry.test(value)
}

export type CompanyConditionDisplay = { lead: string; notes: string[] }

const headingLabels: Record<string, string> = {
  신청자격: '신청 자격은', 신청요건: '신청 요건은', 신청대상: '신청 대상은',
  지원자격: '지원 자격은', 지원대상: '지원 대상은', 지원요건: '지원 요건은',
  참여자격: '참여 자격은', 참여대상: '참여 대상은',
  우대사항: '우대 사항은', 우대조건: '우대 조건은', 가점: '가점 기준은',
  제외대상: '신청 제외 대상은',
}

function cleanConditionLine(line: string): string {
  // Decorations only: never erase numbers, operators, qualification parentheses or clause text.
  let value = line.replace(/^(?:공통\s*사항)\s*(?=[□■(（])/u, '')
    .replace(/[□■☐☑☒▣▢▪▫◦◆◇▶▷▸▹]+(?=\s|[（(가-힣])/gu, ' ')
    .replace(/^[\s□■☐☑☒▣▢▪▫◦○●◆◇▶▷▸▹]+/u, '')
    .replace(/^[①-⑳]\s*/u, '')
    .replace(/[｢｣「」『』]/gu, '')
    .replace(/[ \t]+/gu, ' ').trim()
  value = value.replace(/^(?:\(|（)([^()（）]{2,12})(?:\)|）)\s*[:：]?\s*/u, (whole, label: string) => {
    const replacement = headingLabels[label.replace(/\s+/gu, '')]
    return replacement ? replacement + ' ' : whole
  })
  value = value.replace(/^<\s*(예외\s*대상|예외|참고)\s*>\s*/u, '$1: ')
  return value.replace(/제\s*(\d+)\s*(조|항|호)/gu, '제 $1$2').trim()
}

type ConditionListMarker = { style: 'circled' | 'number' | 'letter'; ordinal: number; indent: number }

function conditionListMarker(raw: string): ConditionListMarker | null {
  const line = raw.trimStart()
  const indent = raw.length - line.length
  const circled = /^([①-⑳])(?=\s|[가-힣(（「｢])/u.exec(line)
  if (circled) return { style: 'circled', ordinal: circled[1].charCodeAt(0) - '①'.charCodeAt(0) + 1, indent }
  // Require a Korean clause or label after a numbered prefix, not a decimal, unit or formula.
  const numbered = /^([1-9]\d?)[.)]\s*(?=[가-힣(（「｢])/u.exec(line)
  if (numbered) return { style: 'number', ordinal: Number(numbered[1]), indent }
  const letter = /^([가나다라마바사아자차카타파하])[.)]\s*(?=[가-힣(（「｢])/u.exec(line)
  return letter ? { style: 'letter', ordinal: '가나다라마바사아자차카타파하'.indexOf(letter[1]) + 1, indent } : null
}

/** Presentation only. rule.text and source excerpts remain untouched for evidence verification. */
export function formatCompanyCondition(text: string): CompanyConditionDisplay {
  const prepared = text.replace(/\r\n?/gu, '\n').replace(/[\u00a0\u200b\ufeff]/gu, ' ')
    .replace(/(^|[\s(])\*\*(\S[^\n]*?\S)\*\*(?=$|[\s).,])/gu, '$1$2')
  const main: string[] = []
  const notes: string[] = []
  let inNote = false
  let bodyList: ConditionListMarker | null = null
  const noteLists = new Map<ConditionListMarker['style'], number>()
  for (const raw of prepared.split('\n')) {
    const line = raw.trim()
    if (!line) continue
    const note = /^(?:※\s*|[*＊]{1,2}\s*(?!(?:가중치|계수|비율|단가|수량|환율)(?:\s|$))(?=[가-힣「｢]))/u.exec(line)
    const heading = /^[□■☐☑☒▣▢▪▫◦○●◆◇▶▷▸▹]/u.test(line)
    const marker = conditionListMarker(raw)
    const resumesBodyList = Boolean(inNote && marker && bodyList
      && marker.style === bodyList.style && marker.ordinal === bodyList.ordinal + 1
      && marker.indent <= bodyList.indent
      && (!noteLists.has(marker.style) || marker.indent < noteLists.get(marker.style)!))
    if (note) {
      const content = cleanConditionLine(line.slice(note[0].length))
      if (content) notes.push(content)
      inNote = true
      noteLists.clear()
      continue
    }
    const value = cleanConditionLine(line)
    if (!value) continue
    if (inNote && !heading && !resumesBodyList && notes.length) {
      if (marker) noteLists.set(marker.style, Math.min(noteLists.get(marker.style) ?? marker.indent, marker.indent))
      notes[notes.length - 1] += ' ' + value
    } else {
      if (heading || resumesBodyList) inNote = false
      if (heading) bodyList = null
      if (marker) bodyList = marker
      if (main.length && !heading && !marker && !/^(?:신청|접수|지원)\s*(?:자격|대상|기간|방법)\s*[:：]/u.test(value)) main[main.length - 1] += ' ' + value
      else main.push(value)
    }
  }
  let lead = main.join('\n')
  if (notes.length) {
    lead = lead.replace(/(달성|충족|대상|기준|기업|이상|이하|초과|미만|보유|해당|가능)[*＊]{1,2}(?=\s|$|[.,])/gu, '$1')
  }
  return { lead, notes }
}
