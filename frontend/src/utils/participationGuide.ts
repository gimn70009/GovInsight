import type { ParticipationGuide, ParticipationRule } from '../api/types'

export const participationSections = [
  { key: 'BASIC', title: '기본 신청 자격', note: '신청 대상과 충족해야 하는 기준' },
  { key: 'TARGETED', title: '특정 기업에 적용되는 제한', note: '해당하는 대상만 확인하세요' },
  { key: 'EXCLUSION', title: '신청 제외 사유', note: '제외 기준과 예외를 함께 확인하세요' },
  { key: 'PREFERENCE', title: '우대·가점', note: '신청 자격과 별도로 적용되는 우대 기준' },
  { key: 'REVIEW', title: '추가 확인', note: '적용 구분이나 기준을 원문에서 확인해야 합니다' },
] as const

export function groupParticipationRules(guide?: ParticipationGuide) {
  // Compatibility for saved responses produced before presentation rules were added.
  const rules: ParticipationRule[] = guide?.rules ?? (guide?.conditions ?? []).map(item => ({
    section: ({ MANDATORY: 'BASIC', PREFERENCE: 'PREFERENCE', EXCLUSION: 'EXCLUSION', REVIEW_REQUIRED: 'REVIEW' } as const)[item.kind] ?? 'REVIEW',
    label: item.category, text: item.text, scope: item.scope, audience: null,
    details: item.details ?? [], exceptions: [], guidance: [], outcomes: [], sources: [item.source],
  }))
  return participationSections.map(section => {
    const groups: { scope: string | null; labels: { label: string; rules: ParticipationRule[] }[] }[] = []
    for (const item of rules) {
      const knownSection = participationSections.some(value => value.key === item.section) ? item.section : 'REVIEW'
      if (knownSection !== section.key || !item.text?.trim() || !item.sources?.some(source => source?.excerpt?.trim())) continue
      const scope = item.scope?.trim() || null
      let group = groups.find(value => value.scope === scope)
      if (!group) { group = { scope, labels: [] }; groups.push(group) }
      const label = item.label?.trim() || '참여 자격'
      let labeled = group.labels.find(value => value.label === label)
      if (!labeled) { labeled = { label, rules: [] }; group.labels.push(labeled) }
      labeled.rules.push(knownSection === item.section ? item : { ...item, section: 'REVIEW' })
    }
    return { ...section, groups }
  }).filter(section => section.groups.length > 0)
}
