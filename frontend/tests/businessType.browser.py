"""Representative type only, mobile, and polling state regression."""
import asyncio
import importlib.util
import json
import sys
from pathlib import Path

from playwright.async_api import async_playwright, expect

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('business_summary', Path(__file__).with_name('businessSummary.browser.py'))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)
refresh = summary.refresh
OUT = Path('frontend/dist/business-type-checks')


def classified():
    data = summary.ready()
    data['businessType'] = json.loads(Path('backend/build/business-type-checks/cbam.json').read_text(encoding='utf-8'))
    data['title'] = '2026 중소기업 CBAM 대응 인프라구축 사업 4차 모집공고'
    data['businessType']['explanation'] = '기업의 배출량을 관리하는 시스템 구축을 지원합니다.'
    data['businessType']['details'] = ['유형1은 배출량 관리 시스템을 구축하고, 유형2는 제3자 검증 비용을 지원합니다.']
    data['businessType']['sources'][0].update(origin='ATTACHMENT', attachmentName='사업 공고문.pdf')
    return data


async def screen(browser):
    h = await refresh.Harness(browser).start()
    try:
        h.states[1] = classified()
        await h.open(analysis_tab=False)
        facts = h.page.get_by_role('table', name='사업 핵심 정보')
        assert await facts.locator('th').all_text_contents() == ['사업 유형', '사업 담당기관', '사업명', '지원규모', '사업 설명', '제출기한']
        row = facts.locator('.business-summary__type-row')
        await expect(row.locator('.business-summary__type-label')).to_have_text('설비·시스템 도입')
        await expect(row.locator('td')).to_have_text('설비·시스템 도입')
        await expect(facts.locator('tbody > tr')).to_have_count(6)
        await expect(row.locator('p, ul, details, blockquote, a, button')).to_have_count(0)
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            assert await facts.evaluate('el => el.scrollWidth <= el.clientWidth + 1'), width
            await h.page.locator('.business-summary__card').first.screenshot(path=str(OUT / f'type-static-{width}.png'))
        await h.page.set_viewport_size(dict(width=1440, height=1100))
        await h.page.locator('.document-row').filter(has_text='상세 갱신 공고 2').evaluate('el => el.click()')
        await h.wait_request(2, 1)
        await expect(row).to_contain_text('확인 필요')
        await expect(row).not_to_contain_text('설비·시스템 도입')
    finally:
        await h.close()


async def states(browser):
    h = await refresh.Harness(browser).start()
    try:
        await h.open(analysis_tab=False)
        row = h.page.locator('.business-summary__type-row')
        await expect(row).to_contain_text('분석 중')
        h.states[1] = classified()
        await h.tick()
        await expect(row.locator('.business-summary__type-label')).to_have_text('설비·시스템 도입')
        h.states[2] = summary.ready()
        h.states[2]['detectionId'] = 2
        h.states[2]['businessType'] = dict(status='NOT_APPLICABLE', code=None, label=None, description=None,
                                         explanation='선정 결과 안내입니다.', details=[], sources=[])
        await h.page.locator('.document-row').filter(has_text='상세 갱신 공고 2').evaluate('el => el.click()')
        await h.wait_request(2, 1)
        await expect(row).to_contain_text('해당 없음')
        await expect(row.locator('td')).to_have_text('해당 없음')
    finally:
        await h.close()


async def long_label(browser):
    h = await refresh.Harness(browser).start()
    try:
        h.states[1] = classified()
        h.states[1]['businessType'].update(code='ADVISORY', label='모델이 만든 임의 라벨',
                                          description='전문가의 조사와 컨설팅을 지원합니다.')
        await h.open(analysis_tab=False)
        row = h.page.locator('.business-summary__type-row')
        await expect(row.locator('strong')).to_have_text('조사·컨설팅·전문지원')
        await expect(row).not_to_contain_text('임의 라벨')
        await h.page.set_viewport_size(dict(width=320, height=1100))
        assert await row.evaluate('el => el.scrollWidth <= el.clientWidth + 1')
        await expect(row.get_by_role('button')).to_have_count(0)
    finally:
        await h.close()


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel='msedge')
        try:
            await screen(browser)
            await states(browser)
            await long_label(browser)
        finally:
            await browser.close()
    print('PASS: single business type above organizations, one label only, no descriptions or disclosure/evidence controls, 4 viewport widths, legacy/ordinary states, polling and notice switching')


if __name__ == '__main__':
    asyncio.run(main())
