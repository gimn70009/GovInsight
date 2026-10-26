import type { MonitoringSource, MonitoringSourceSettings } from '../api/types'

export interface SourceDraft {
  detailFetchCount: string
  enabled: boolean
}

export type SourceDrafts = Record<number, SourceDraft>

export function sourceDraft(source: MonitoringSource, drafts: SourceDrafts): SourceDraft {
  return drafts[source.sourceId] ?? { detailFetchCount: String(source.detailFetchCount), enabled: source.enabled }
}

export function validCollectionCount(value: string): boolean {
  const count = Number(value)
  return value.trim() !== '' && Number.isInteger(count) && count >= 1 && count <= 2147483647
}

export function sourceSettingsChanges(sources: MonitoringSource[], drafts: SourceDrafts) {
  const updates: MonitoringSourceSettings[] = []
  const invalidSourceIds: number[] = []
  for (const source of sources) {
    const draft = sourceDraft(source, drafts)
    if (!validCollectionCount(draft.detailFetchCount)) {
      invalidSourceIds.push(source.sourceId)
    } else if (Number(draft.detailFetchCount) !== source.detailFetchCount || draft.enabled !== source.enabled) {
      updates.push({ sourceId: source.sourceId, detailFetchCount: Number(draft.detailFetchCount), enabled: draft.enabled })
    }
  }
  return { updates, invalidSourceIds, dirtyCount: updates.length + invalidSourceIds.length }
}
