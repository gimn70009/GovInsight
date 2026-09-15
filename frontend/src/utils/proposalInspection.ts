import type { ProposalSource, ProposalTemplateInspection } from '../api/types'

export const proposalSourceKey = (source: { attachmentId: number; partIndex: number }) =>
  `${source.attachmentId}:${source.partIndex}`

// A failed or unfinished check never turns into a negative classification.
export function inspectedSource(source: ProposalSource, result?: ProposalTemplateInspection, hasSavedDraft = false): ProposalSource {
  return {
    ...source,
    available: source.available && (hasSavedDraft || (result?.status === 'WRITABLE' && result.sectionTitles.length > 0)),
    reason: !source.available ? source.reason : result?.message ?? '',
  }
}

export async function inspectSources(
  sources: ProposalSource[],
  inspect: (source: ProposalSource, signal: AbortSignal) => Promise<ProposalTemplateInspection>,
  publish: (source: ProposalSource, result: ProposalTemplateInspection) => void,
  signal: AbortSignal,
  savedSourceKeys: ReadonlySet<string> = new Set(),
) {
  const queue = sources.filter((source) => source.available && !savedSourceKeys.has(proposalSourceKey(source)))
  let next = 0
  async function worker() {
    while (!signal.aborted && next < queue.length) {
      const source = queue[next++]
      let result: ProposalTemplateInspection
      try {
        result = await inspect(source, signal)
        if (result.status === 'WRITABLE' && !result.sectionTitles?.length) {
          throw new Error('Missing verified headings')
        }
      } catch {
        result = { status: 'UNAVAILABLE', sectionTitles: [],
          message: '작성란 확인을 완료하지 못했습니다. 다시 확인해 주세요.' }
      }
      if (!signal.aborted) publish(source, result)
    }
  }
  await Promise.all([worker(), worker()])
}
