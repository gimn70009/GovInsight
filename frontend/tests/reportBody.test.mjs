import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import test from 'node:test'
import { runInNewContext } from 'node:vm'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import ts from 'typescript'

// Node cannot load TSX/CSS directly. Compile the real component with the existing
// TypeScript dependency and ignore its stylesheet for server-side markup checks.
const componentUrl = new URL('../src/components/ReportBody.tsx', import.meta.url)
const source = readFileSync(componentUrl, 'utf8').replace(/^import '\.\/ReportBody\.css'\r?\n/m, '')
const compiled = ts.transpileModule(source, {
  compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS },
}).outputText
const componentModule = { exports: {} }
runInNewContext(compiled, {
  require: createRequire(componentUrl), module: componentModule, exports: componentModule.exports,
  URL,
})
const ReportBody = componentModule.exports.default
const render = (body) => renderToStaticMarkup(createElement(ReportBody, { body }))
const separator = '\n\n────────────────\n\n'

test('business facts retain each amount and eligibility condition with an in-place application link', () => {
  const body = '신규 1건' + separator + [
    '▸ 공동연구 공고', '기관: 공공기관',
    '문서 유형: 일반공지 │ 신규 │ 기회점수: 69점',
    '공고 성격: 사업 기회', '사업 유형: R&D·기술개발', '사업 방식: 연구개발비 지원',
    '요약: 기술개발을 지원합니다.',
    '• 금액·규모: 과제당 연 최대 5억 원, 최대 3년',
    '  ↳ 중소기업 자부담 25% 이상',
    '• 참여 역할: 국내 주관연구개발기관',
    '• 제출 주체·대상: 중소·중견기업',
    '  ↳ 기업부설연구소 보유 필수',
    '  ↳ 대기업 단독 신청 불가',
    '• 제출처·방법: 국문 접수: [K-PASS 온라인 접수](https://example.org/apply?a=1&b=2)',
    '원문: [게시글 보기](https://example.org/notice)',
  ].join('\n')
  const html = render(body)
  assert.equal((html.match(/class="report-fact"/g) ?? []).length, 4)
  assert.match(html, /<dt>금액·규모<\/dt><dd><ul><li>과제당 연 최대 5억 원, 최대 3년<\/li><li>중소기업 자부담 25% 이상<\/li>/)
  assert.match(html, /<dt>참여 역할<\/dt><dd><ul><li>국내 주관연구개발기관<\/li>/)
  assert.match(html, /<li>기업부설연구소 보유 필수<\/li><li>대기업 단독 신청 불가<\/li>/)
  assert.match(html, /<dt>제출처·방법<\/dt><dd><ul><li>국문 접수: <a [^>]*href="https:\/\/example.org\/apply\?a=1&amp;b=2"/)
  assert.ok(html.includes('사업 유형: R&amp;D·기술개발'))
})

test('general notices and legacy reports keep their summary without fabricated business fields', () => {
  const notice = render('신규 1건' + separator + [
    '▸ 기관 소식', '기관: 공공기관', '문서 유형: 일반공지 │ 신규 │ 기회점수: 20점',
    '공고 성격: 일반 공지', '요약: 기관 운영시간을 변경합니다.',
    '• 공고일: 2026년 10월 13일', '원문: [게시글 보기](https://example.org/news)',
  ].join('\n'))
  assert.ok(notice.includes('공고 성격: 일반 공지'))
  assert.ok(notice.includes('요약: 기관 운영시간을 변경합니다.'))
  assert.doesNotMatch(notice, /금액·규모|참여 역할|사업 유형|사업 방식/)
  const legacy = render('▸ 구형 보고서\n요약: 저장된 요약입니다.\n• 문의 담당: 담당 부서\n원문: https://example.org/news')
  assert.ok(legacy.includes('요약: 저장된 요약입니다.'))
  assert.match(legacy, /<dt>문의 담당<\/dt>/)
})

test('new field values escape source markup and reject unsafe application links', () => {
  const html = render([
    '▸ <script>제목</script>', '사업 유형: <img src=x onerror=alert(1)>',
    '• 금액·규모: <b>5억 원</b>',
    '• 참여 역할: [실행](javascript:alert(1))',
    '• 제출처·방법: [로그인](https://user:password@example.org/apply)',
    '  ↳ [주소](https://example.org\\evil)',
    '  ↳ [정상 접수](https://example.org/apply?x=1&y=2)',
  ].join('\n'))
  assert.doesNotMatch(html, /<script>|<img|<b>5억/)
  assert.ok(html.includes('&lt;b&gt;5억 원&lt;/b&gt;'))
  assert.equal((html.match(/<a /g) ?? []).length, 1)
  assert.match(html, /href="https:\/\/example.org\/apply\?x=1&amp;y=2" target="_blank" rel="noopener noreferrer"/)
})
