/** Visual line breaks only: retain wording, logic, punctuation and parenthetical groups. */
export function conditionDetailLines(text: string): string[] {
  const value = text.trim().replace(/^[*•◦▪▷]\s+/, '')
  const lines: string[] = []
  const stack: string[] = []
  const pairs: Record<string, string> = { '(': ')', '[': ']', '{': '}', '（': '）', '「': '」', '『': '』', '“': '”', '‘': '’' }
  let start = 0
  for (let index = 0; index < value.length; index++) {
    const character = value[index]
    if (pairs[character]) stack.push(pairs[character])
    else if (stack.at(-1) === character) stack.pop()
    else if ((character === ';' || character === '；') && !stack.length) {
      lines.push(value.slice(start, index + 1).trim())
      start = index + 1
    }
  }
  const tail = value.slice(start).trim()
  if (tail) lines.push(tail)
  return lines.length ? lines : [value]
}
