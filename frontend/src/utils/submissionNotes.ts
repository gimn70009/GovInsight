import type { SubmissionDocument, SubmissionGuide } from '../api/types'

type Note = SubmissionGuide['notes'][number]
const key = (value: string) => value.normalize('NFKC').replace(/\s+/gu, '')
export const submissionNoteKey = (value: string) => key(value.replace(/^[*※◦]+\s*/u, '').replace(/[.。]$/u, ''))

// Scope is supplied by the source reader; the UI never infers applicability from validity/date words.
export function submissionNoteGroups(documents: SubmissionDocument[], notes: Note[] = []) {
  const common: Note[] = []
  const byDocument: Note[][] = documents.map(() => [])
  const unassigned: Note[] = []
  for (const note of notes) {
    if (!note.text.trim() || !note.source?.excerpt?.trim() || note.source.sectionTitle === '제출서류 표 확인 필요') continue
    if (!note.appliesTo?.trim()) {
      common.push(note)
      continue
    }
    const scope = key(note.appliesTo)
    let matches = documents.flatMap((item, index) => {
      const names = [item.title, ...(item.documentNames ?? [])]
      const scopes = [...names, ...names.map(name => `${name} · ${item.appliesTo ?? ''}`)]
      const sameFile = item.source?.origin === note.source?.origin
        && (item.source?.attachmentName ?? null) === (note.source?.attachmentName ?? null)
      return sameFile && scopes.some(name => key(name) === scope) ? [index] : []
    })
    if (matches.length > 1 && note.source.location) {
      const located = matches.filter(index => documents[index].source?.location === note.source?.location)
      if (located.length > 0) matches = located
    }
    // Equal document names in different stages/tables must not borrow each other's instructions.
    if (matches.length > 1) {
      const quote = key(note.source.excerpt)
      matches = matches.filter(index => {
        const rowQuote = key(documents[index].source?.excerpt ?? '')
        return rowQuote.length > 0 && (quote.includes(rowQuote) || rowQuote.includes(quote))
      })
    }
    if (matches.length === 0) unassigned.push(note)
    else for (const index of matches) byDocument[index].push(note)
  }
  return { common, byDocument, unassigned }
}
