const submissionReviewPrefix = /^제출\s*여부\s*확인\s*:/

export function visibleMeetingAgenda(agenda: string[] = []): string[] {
  // Older responses stored generated file checks in the meeting agenda.
  return [...new Set(agenda.filter(item => !submissionReviewPrefix.test(item.trim())))]
}
