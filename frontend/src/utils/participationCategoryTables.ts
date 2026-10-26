import type { ParticipationCategory, ParticipationGuide, ParticipationCriterionEntry } from '../api/types'
import { participationCategories } from './participationCategories'

function groupEvidence(entries: ParticipationCriterionEntry[]): ParticipationCriterionEntry[] {
  const filtered = entries.filter(entry => entry.rule.section !== 'REVIEW')
  const verified = filtered.filter(entry => entry.displayStatus === 'VERIFIED')
  const evidence = filtered.filter(entry => entry.displayStatus !== 'VERIFIED')
  const first = evidence[0]
  if (!first) return verified
  return [...verified, { ...first, displayStatus: 'EVIDENCE_ONLY', rule: {
    ...first.rule, text: '', sources: evidence.flatMap(entry => entry.rule.sources),
  } }]
}

/** Keep every requested row visible, including during a server upgrade or before text is available. */
export function categoryTables(guide: ParticipationGuide | null | undefined): ParticipationCategory[] {
  return participationCategories.map(category => {
    const supplied = guide?.categories?.find(value => value.code === category.code)
    const rows = category.rows.map(row => {
      const value = supplied?.rows.find(value => value.code === row.code)
      return { ...row, status: value?.status ?? 'REVIEW_REQUIRED' as const, entries: groupEvidence(value?.entries ?? []) }
    })
    return { code: category.code, label: category.label, rows: [
      ...rows, ...(supplied?.rows.filter(row => row.code === 'OTHER').map(row => ({
        ...row, label: '그 밖의 명시 조건', entries: groupEvidence(row.entries),
      })).filter(row => row.entries.length > 0) ?? []),
    ] }
  })
}
