"""Business summary tables: preserved qualifiers, grounded documents, legacy and live refresh."""
import asyncio
import importlib.util
import re
import sys
from pathlib import Path
from playwright.async_api import async_playwright, expect

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('detail_refresh', Path(__file__).with_name('documentDetailRefresh.browser.py'))
refresh = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = refresh
spec.loader.exec_module(refresh)
OUT = Path('frontend/dist/business-summary-checks')
AMOUNT = '과제당 연간 최대 2억 원, 2년 이내\n정부지원 비율 75% 이내 / 자부담 25% 이상'
DEADLINE = '온라인 접수: 2026년 10월 30일 18:00까지\n원본 우편: 2026년 11월 2일 도착분까지'
PURPOSE = '중소기업의 제조 현장에 AI 기술을 적용하고 실증을 지원하는 사업입니다.'


def ready():
    result = refresh.analysis('READY')
    result['businessSummary'] = dict(purpose=PURPOSE, supportScale=AMOUNT)
    result['applicationDeadline'] = DEADLINE
    result['proposal']['preparation'] = dict(meetingAgenda=[], submissionReviewNotes=[], eligibilityChecklist=[], applicationDeadline=None, strategy=dict(decision='CONDITIONAL_GO', decisionReason='조건 확인', recommendedProject='제조 AI 실증', recommendedParticipation='참여 검토', alternativeParticipation='협력 검토', capabilityMatches=[], criticalGaps=[], stopCriteria=[]),
        submissionDocuments=[
            dict(title='사업계획서', detail='지정 양식으로 작성하고 대표자 서명 후 제출합니다.', nextAction='서류 준비',
                 requirementLevel='MANDATORY', stage='APPLICATION', appliesTo='모든 신청기업',
                 source=dict(origin='ATTACHMENT', attachmentName='사업 공고문.pdf', sectionTitle='제출서류', location='5쪽', excerpt='모든 신청기업은 서명한 사업계획서를 접수 마감일까지 제출한다.')),
            dict(title='이행보증보험증권', detail='선정 기업에 한해 협약 시 제출합니다.', nextAction='선정 후 준비',
                 requirementLevel='CONDITIONAL', stage='AGREEMENT', appliesTo='최종 선정 기업',
                 source=dict(origin='NOTICE_BODY', sectionTitle='협약', excerpt='최종 선정 기업은 협약 체결 시 이행보증보험증권을 제출한다.')),
            dict(title='추천 실적자료', detail='실적 자료가 있으면 좋습니다.', nextAction='검토', requirementLevel='RECOMMENDED', stage='APPLICATION',
                 source=dict(origin='AI_RECOMMENDATION', sectionTitle='권장', excerpt='추천 항목'))])
    data = refresh.detail(pending=False, result=result)
    data['responsibleOrganizations'] = [dict(name='검증 사업운영원', role='시행기관', sourceType='ATTACHMENT', attachmentName='사업 공고문.pdf', excerpt='시행기관: 검증 사업운영원')]
    data['title'] = '2026년 제조 AI 실증 지원사업 참여기업 모집 공고'
    data['attachments'] = [dict(attachmentId=1, fileName='사업 공고문.pdf', fileExtension='pdf', fileSize=1024, parseStatus='COMPLETED', downloadUrl='https://example.org/notice.pdf'),
                           dict(attachmentId=2, fileName='참고자료.pdf', fileExtension='pdf', fileSize=1024, parseStatus='COMPLETED', downloadUrl='https://example.org/reference.pdf')]
    return data


async def table_and_tabs(browser):
    h = await refresh.Harness(browser).start()
    try:
        h.states[1] = ready()
        await h.open(analysis_tab=False)
        await expect(h.page.get_by_role('button', name='사업 요약', exact=True)).to_have_attribute('aria-pressed', 'true')
        facts = h.page.get_by_role('table', name='사업 핵심 정보')
        await expect(facts.get_by_role('row', name='지원규모')).to_contain_text(AMOUNT)
        await expect(facts.get_by_role('row', name='제출기한')).to_contain_text(DEADLINE)
        await expect(facts.get_by_role('row', name='사업 설명')).to_contain_text(PURPOSE)
        await facts.get_by_role('button', name=re.compile('기관 근거 보기')).click()
        await expect(facts.locator('blockquote')).to_have_text('시행기관: 검증 사업운영원')
        await expect(facts.get_by_role('link', name='근거 문서 열기')).to_have_attribute('href', 'https://example.org/notice.pdf')
        await facts.get_by_role('button', name=re.compile('기관 근거 보기')).click()
        await expect(facts.get_by_role('row', name='사업 담당기관')).to_contain_text('검증 사업운영원')
        await expect(facts.get_by_role('row', name='사업 담당기관')).not_to_contain_text('확인 필요')
        await expect(facts.get_by_role('row', name='사업 담당기관')).not_to_contain_text('공고 게시기관')
        await expect(facts).not_to_contain_text('공고명 기준')
        documents = h.page.get_by_role('table', name='제출서류 안내')
        await expect(documents.locator('tbody tr')).to_have_count(2)
        await expect(documents.get_by_role('row', name='이행보증보험증권')).to_contain_text('해당 시 제출')
        await expect(documents.get_by_role('row', name='이행보증보험증권')).to_contain_text('협약 시')
        await expect(documents).not_to_contain_text('추천 실적자료')
        await expect(documents).not_to_contain_text('참고자료.pdf')
        await documents.get_by_role('button', name='사업계획서 근거 보기', exact=True).click()
        await expect(documents.get_by_role('link', name='근거 문서 열기')).to_have_attribute('href', 'https://example.org/notice.pdf')
        await expect(documents.locator('blockquote').first).to_contain_text('모든 신청기업은')
        await documents.get_by_role('button', name='사업계획서 근거 보기', exact=True).click()
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            for selector in ('.drawer--detail', '.detail-tabs', '.business-summary__facts', '.business-summary__documents'):
                assert await h.page.locator(selector).evaluate('el => el.scrollWidth <= el.clientWidth + 1'), (width, selector)
            if width <= 390:
                assert await h.page.locator('.detail-tabs').evaluate('el => getComputedStyle(el).display') == 'grid'
            await h.page.locator('.drawer--detail').evaluate('el => { el.scrollTop = 0 }')
            await h.page.locator('.drawer--detail').screenshot(path=str(OUT / f'summary-{width}.png'))
        await h.page.set_viewport_size(dict(width=1440, height=1100))
        for tab in ('공고 분석', '사업 제안', '유사 공고 비교 0', '사업 요약'):
            await h.page.get_by_role('button', name=tab, exact=True).click()
            await expect(h.page.get_by_role('button', name=tab, exact=True)).to_have_attribute('aria-pressed', 'true')
        await expect(facts).to_be_visible()
    finally:
        await h.close()


async def legacy_and_pending(browser):
    h = await refresh.Harness(browser).start()
    try:
        await h.open(analysis_tab=False)
        await expect(h.page.locator('.business-summary__status')).to_contain_text('자동으로 갱신')
        h.states[1] = ready()
        await h.tick()
        await expect(h.page.locator('.business-summary__amount')).to_have_text(AMOUNT)
        await expect(h.page.locator('.detail-tabs button.active')).to_have_text('사업 요약')
        await h.stopped()
        await h.page.locator('.document-row').filter(has_text='상세 갱신 공고 2').evaluate('el => el.click()')
        await h.wait_request(2, 1)
        await expect(h.page.locator('.business-summary__description')).to_have_text('두 번째 공고의 분석입니다.')
        await expect(h.page.get_by_role('table', name='사업 핵심 정보').get_by_role('row', name='사업 담당기관')).to_contain_text('확인 필요')
        await expect(h.page.get_by_role('table', name='사업 핵심 정보').get_by_role('row', name='지원규모')).to_contain_text('확인 필요')
        await expect(h.page.locator('.business-summary__documents-empty')).to_contain_text('제출서류 확인 필요')
    finally:
        await h.close()
    h = await refresh.Harness(browser).start()
    try:
        h.states[1] = refresh.detail(pending=False)
        await h.open(analysis_tab=False)
        await expect(h.page.locator('.business-summary__status')).to_contain_text('AI 분석 결과가 없습니다')
        await expect(h.page.locator('.business-summary__status')).not_to_contain_text('자동으로')
        await h.stopped()
    finally:
        await h.close()


async def organization_roles_without_analysis(browser):
    h = await refresh.Harness(browser).start()
    try:
        h.states[1] = refresh.detail(pending=False)
        h.states[1]['responsibleOrganizations'] = [
            dict(name='중소벤처기업부', role='총괄기관', sourceType='NOTICE_BODY', attachmentName=None, excerpt='총괄기관\n(중소벤처기업부)'),
            dict(name='중소벤처기업진흥공단', role='시행기관', sourceType='NOTICE_BODY', attachmentName=None, excerpt='시행기관\n(중소벤처기업진흥공단)')]
        h.states[2] = refresh.detail(2, pending=False)
        h.states[2]['responsibleOrganizations'] = [dict(name='대·중소기업·농어업협력재단', role='문의처', sourceType='NOTICE_BODY', attachmentName=None, excerpt='(문의처) 대·중소기업·농어업협력재단')]
        await h.open(analysis_tab=False)
        row = h.page.get_by_role('table', name='사업 핵심 정보').get_by_role('row', name='사업 담당기관')
        await expect(row).to_contain_text('총괄기관')
        await expect(row).to_contain_text('시행기관')
        await expect(row).to_contain_text('중소벤처기업진흥공단')
        await expect(row).not_to_contain_text('확인 필요')
        await row.screenshot(path=str(OUT / 'organizations-operating.png'))
        await h.page.locator('.document-row').filter(has_text='상세 갱신 공고 2').evaluate('el => el.click()')
        await h.wait_request(2, 1)
        await expect(row).to_contain_text('대·중소기업·농어업협력재단')
        await expect(row).to_contain_text('문의처')
        await expect(row).not_to_contain_text('중소벤처기업진흥공단')
        await expect(row).not_to_contain_text('주관기관')
        await expect(row).not_to_contain_text('확인 필요')
        for width in (1440, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            assert await row.evaluate('el => el.scrollWidth <= el.clientWidth + 1')
            await row.screenshot(path=str(OUT / f'organizations-contact-{width}.png'))
            await row.get_by_role('button', name=re.compile('기관 근거 보기')).click()
            await expect(row.locator('blockquote')).to_contain_text('협력재단')
            await row.get_by_role('button', name=re.compile('기관 근거 보기')).click()
    finally:
        await h.close()


async def submission_guide_without_analysis(browser):
    h = await refresh.Harness(browser).start()
    try:
        data = refresh.detail(pending=False)
        data['attachments'] = ready()['attachments']
        data['submissionGuide'] = dict(documents=[
            dict(title='EU 수출실적 증빙양식(해당 시)', detail='유형1 · 유형2 중 EU 수출실적이 있는 기업', requirementLevel='CONDITIONAL', stage='APPLICATION', appliesTo='유형1 · 유형2',
                 source=dict(origin='ATTACHMENT', attachmentName='사업 공고문.pdf', sectionTitle='신청서류', excerpt='[붙임5] EU 수출실적 증빙양식(해당 시)')),
            dict(title='최근 3년간 표준재무제표증명원 각 1부', detail='유형2: 제출 대상 아님', requirementLevel='CONDITIONAL', stage='APPLICATION', appliesTo='유형1',
                 source=dict(origin='ATTACHMENT', attachmentName='사업 공고문.pdf', sectionTitle='신청서류', excerpt='유형1 | 유형2\n최근 3년간 표준재무제표증명원 각 1부\n○ X'))],
            notes=[dict(text='* 모든 서류는 신청일 포함 1개월 이내 발급분 및 접수 마감일 기준 유효서류만 인정', source=dict(origin='ATTACHMENT', attachmentName='사업 공고문.pdf', sectionTitle='신청서류', excerpt='모든 서류는 신청일 포함 1개월 이내 발급분 및 접수 마감일 기준 유효서류만 인정'))])
        h.states[1] = data
        h.states[2] = ready()
        h.states[2]['detectionId'] = 2
        h.states[2]['submissionGuide'] = dict(documents=[], notes=[])
        await h.open(analysis_tab=False)
        table = h.page.get_by_role('table', name='제출서류 안내')
        await expect(table.locator('tbody tr')).to_have_count(2)
        await expect(table).to_contain_text('해당 시 제출')
        await expect(table.get_by_text('유형2: 제출 대상 아님', exact=True)).to_be_hidden()
        await table.get_by_role('button', name=re.compile('최근 3년간.*조건 보기')).click()
        await expect(table.get_by_text('유형2: 제출 대상 아님', exact=True)).to_be_visible()
        notes = h.page.get_by_role('complementary', name='제출 유의사항')
        await expect(notes).to_contain_text('1개월 이내')
        await notes.get_by_text('근거 보기', exact=True).click()
        await expect(notes.get_by_role('link', name='근거 문서 열기')).to_have_attribute('href', 'https://example.org/notice.pdf')
        for width in (1440, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            assert await table.evaluate('el => el.scrollWidth <= el.clientWidth + 1')
            assert await notes.evaluate('el => el.scrollWidth <= el.clientWidth + 1')
        await h.stopped()
        await h.page.locator('.document-row').filter(has_text='상세 갱신 공고 2').evaluate('el => el.click()')
        await h.wait_request(2, 1)
        await expect(table).to_have_count(0)
        await expect(notes).to_have_count(0)
        await expect(h.page.locator('.business-summary__documents-empty')).to_contain_text('제출서류 확인 필요')
    finally:
        await h.close()


async def official_submission_fixtures(browser):
    # Optional offline integration: output produced by the Java resolver over official HWPX fixtures.
    import json
    fixture_dir = refresh.os.environ.get('SUBMISSION_CHECK_FIXTURES')
    if not fixture_dir:
        return
    h = await refresh.Harness(browser).start()
    try:
        for identifier, notice in ((1, '1071591'), (2, '1071647')):
            data = ready()
            data['detectionId'] = identifier
            data['analysis']['proposal']['preparation'] = None
            data['submissionGuide'] = json.loads((Path(fixture_dir) / f'{notice}.submission.json').read_text(encoding='utf-8'))
            data['responsibleOrganizations'] = json.loads((Path(fixture_dir) / f'{notice}.organizations.json').read_text(encoding='utf-8'))
            data['attachments'] = [dict(fileName=f'{notice}_공고.hwpx', fileExtension='hwpx', fileSize=1024, parseStatus='COMPLETED', downloadUrl=f'https://www.mss.go.kr/common/board/Download.do?bcIdx={notice}')]
            data['title'] = '2026 중소기업 CBAM 대응 인프라구축 사업 4차 모집공고' if identifier == 1 else '2026년 납품대금 연동 우수기업 포상 모집 수정공고(연장)'
            h.states[identifier] = data
        await h.open(analysis_tab=False)
        table = h.page.get_by_role('table', name='제출서류 안내')
        await expect(table.locator('tbody tr')).to_have_count(11)
        await table.get_by_role('button', name=re.compile('최근 3년간.*조건 보기')).click()
        await expect(table.get_by_role('row', name='최근 3년간')).to_contain_text('유형2: 제출 대상 아님')
        await expect(h.page.get_by_role('complementary', name='제출 유의사항')).to_contain_text('1개월 이내')
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            for selector in ('.drawer--detail', '.business-summary__documents', '.business-summary__submission-notes'):
                assert await h.page.locator(selector).evaluate('el => el.scrollWidth <= el.clientWidth + 1'), (width, selector, await h.page.locator(selector).evaluate('el => ({scroll: el.scrollWidth, client: el.clientWidth})'))
            assert await table.locator('th, td').evaluate_all('cells => cells.every(el => el.scrollWidth <= el.clientWidth + 1)'), width
            await h.page.locator('.drawer--detail').evaluate('el => el.scrollTop = el.querySelector(".business-summary__documents-heading").offsetTop - 30')
            await h.page.screenshot(path=str(OUT / f'submission-cbam-{width}.png'), animations='disabled')
        await h.page.set_viewport_size(dict(width=1440, height=1100))
        await h.page.locator('.document-row').filter(has_text='상세 갱신 공고 2').evaluate('el => el.click()')
        await h.wait_request(2, 1)
        await expect(table.locator('tbody tr')).to_have_count(5)
        await expect(table).to_contain_text('공적조서')
        await expect(table).not_to_contain_text('EU 수출실적')
        await expect(h.page.get_by_role('complementary', name='제출 유의사항')).to_contain_text('PDF 및 HWP')
        await h.page.locator('.drawer--detail').evaluate('el => el.scrollTop = el.querySelector(".business-summary__documents-heading").offsetTop - 30')
        await h.page.screenshot(path=str(OUT / 'submission-award-1440.png'), animations='disabled')
    finally:
        await h.close()


async def compact_organizations_and_amounts(browser):
    h = await refresh.Harness(browser).start()
    try:
        data = ready()
        data['title'] = '2026 중소기업 CBAM 대응 인프라구축 사업 4차 모집공고'
        data['analysis']['businessSummary']['supportScale'] = '유형1은 기업당 최대 42백만원(보조율 70%)로 H/W·S/W 및 검증을 지원하고 유형2는 검증비용으로 기업당 최대 3.5백만원을 지원합니다.'
        data['responsibleOrganizations'] = [
            dict(name='중소벤처기업부', role='총괄기관', sourceType='NOTICE_BODY', attachmentName=None, excerpt='총괄기관: 중소벤처기업부'),
            dict(name='중소벤처기업진흥공단', role='시행기관', sourceType='ATTACHMENT', attachmentName='사업 공고문.pdf', excerpt='시행기관: 중소벤처기업진흥공단\n기업당 최대 42백만원')]
        h.states[1] = data
        await h.open(analysis_tab=False)
        facts = h.page.get_by_role('table', name='사업 핵심 정보')
        await expect(facts).not_to_contain_text('공고 게시기관')
        await expect(facts).not_to_contain_text('공고명 기준')
        await expect(h.page.locator('.business-summary__amount')).to_have_text('유형1은 기업당 최대 4,200만 원(보조율 70%)로 H/W·S/W 및 검증을 지원하고 유형2는 검증비용으로 기업당 최대 350만 원을 지원합니다.')
        buttons = facts.get_by_role('button', name=re.compile('기관 근거 보기'))
        await expect(buttons).to_have_count(2)
        first, second = buttons.nth(0), buttons.nth(1)
        await first.focus()
        await h.page.keyboard.press('Enter')
        await expect(first).to_have_attribute('aria-expanded', 'true')
        await expect(second).to_have_attribute('aria-expanded', 'false')
        await expect(facts.get_by_text('총괄기관: 중소벤처기업부', exact=True)).to_be_visible()
        await h.page.keyboard.press('Space')
        await expect(first).to_have_attribute('aria-expanded', 'false')
        await expect(facts.get_by_text('총괄기관: 중소벤처기업부', exact=True)).to_be_hidden()
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            for organization in await facts.locator('.business-summary__organization').all():
                name = await organization.locator('strong').bounding_box()
                button = await organization.get_by_role('button').bounding_box()
                assert button['x'] >= name['x'] + name['width'] - 1, (width, name, button)
                assert abs((name['y'] + name['height'] / 2) - (button['y'] + button['height'] / 2)) < 2
            assert await facts.evaluate('el => el.scrollWidth <= el.clientWidth + 1'), width
            await facts.screenshot(path=str(OUT / f'compact-summary-{width}.png'))
            await second.click()
            await expect(second).to_have_attribute('aria-expanded', 'true')
            # Evidence is preserved verbatim even though the summary amount is converted.
            await expect(facts.locator('blockquote').last).to_contain_text('42백만원')
            await expect(facts.get_by_role('link', name='근거 문서 열기')).to_have_attribute('href', 'https://example.org/notice.pdf')
            await facts.screenshot(path=str(OUT / f'compact-evidence-{width}.png'))
            assert await facts.evaluate('el => el.scrollWidth <= el.clientWidth + 1'), width
            await second.click()
    finally:
        await h.close()


async def compact_missing_organization(browser):
    h = await refresh.Harness(browser).start()
    try:
        h.states[1] = ready()
        h.states[1]['responsibleOrganizations'] = []
        await h.open(analysis_tab=False)
        facts = h.page.get_by_role('table', name='사업 핵심 정보')
        row = facts.get_by_role('row', name='사업 담당기관')
        help_panel = row.locator('.business-summary__missing-help')
        toggle = row.get_by_role('button', name='확인 필요 안내', exact=True)
        explanation = help_panel.locator('p')
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1000))
            await expect(toggle).to_be_visible()
            await expect(toggle).to_have_attribute('aria-expanded', 'false')
            await expect(explanation).to_be_hidden()
            assert '확인하지 못했습니다' not in await row.inner_text()
            collapsed = await row.bounding_box()
            assert collapsed['height'] < 85, (width, collapsed)
            assert await row.evaluate('el => el.scrollWidth <= el.clientWidth + 1')
            await row.screenshot(path=str(OUT / f'missing-organization-{width}.png'))
            await toggle.focus()
            await h.page.keyboard.press('Enter')
            await expect(explanation).to_be_visible()
            await expect(explanation).to_contain_text('공고 원문을 확인해 주세요.')
            await expect(toggle).to_have_attribute('aria-expanded', 'true')
            assert await row.evaluate('el => el.scrollWidth <= el.clientWidth + 1')
            await row.screenshot(path=str(OUT / f'missing-organization-open-{width}.png'))
            await h.page.keyboard.press('Space')
            await expect(explanation).to_be_hidden()
            # Pointer activation uses the same button as keyboard activation.
            await toggle.click()
            await expect(explanation).to_be_visible()
            await toggle.click()
            await expect(explanation).to_be_hidden()
        await toggle.click()
        await expect(explanation).to_be_visible()
        await h.page.locator('.document-row').filter(has_text='상세 갱신 공고 2').evaluate('el => el.click()')
        await h.wait_request(2, 1)
        await expect(explanation).to_be_hidden()
        await expect(toggle).to_be_visible()
    finally:
        await h.close()


async def readable_submission_notes(browser):
    import json
    h = await refresh.Harness(browser).start()
    try:
        data = ready()
        source = dict(origin='ATTACHMENT', attachmentName='사업 공고문.pdf', sectionTitle='신청서류',
                      excerpt='□ 중소기업(소상공인) 확인서\n* 신청일 기준 기간유효 必, 소상공인 명시 必')
        guide = dict(documents=[dict(title='중소기업(소상공인) 확인서', source=source)], notes=[
            dict(text='신청일 기준으로 유효한 서류여야 합니다. ‘소상공인’임이 명시되어 있어야 합니다.', appliesTo='중소기업(소상공인) 확인서', source=source)])
        fixture = refresh.os.environ.get('SUBMISSION_NOTE_CHECK_FIXTURE')
        if fixture:
            guide = json.loads(Path(fixture).read_text(encoding='utf-8'))
            data['attachments'] = [dict(fileName='클린제조환경_공고.hwpx', fileExtension='hwpx', fileSize=1024, parseStatus='COMPLETED', downloadUrl='https://example.org/notice.pdf')]
        data['submissionGuide'] = guide
        h.states[1] = data
        h.states[2] = dict(data, detectionId=2)
        await h.open(analysis_tab=False)
        notes = h.page.get_by_role('complementary', name='제출 유의사항')
        rows = notes.locator('li')
        await expect(rows).to_have_count(len(guide['notes']))
        await expect(notes).not_to_contain_text('제출서류 없음')
        for index, note in enumerate(guide['notes']):
            row = rows.nth(index)
            await expect(row.locator('.business-summary__note-line > p')).to_have_text(note['text'])
            if note.get('appliesTo'):
                await expect(row.locator('.business-summary__note-scope')).to_have_text(note['appliesTo'])
            await expect(row.locator('.business-summary__note-source')).to_be_hidden()
        first = rows.first
        button = first.get_by_role('button', name=re.compile('근거 보기'))
        evidence = first.locator('.business-summary__note-source')
        OUT.mkdir(parents=True, exist_ok=True)
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            await expect(button).to_have_attribute('aria-expanded', 'false')
            for row in await rows.all():
                paragraph = await row.locator('.business-summary__note-line > p').bounding_box()
                toggle = await row.get_by_role('button').bounding_box()
                assert toggle['x'] >= paragraph['x'] + paragraph['width'] - 1, (width, paragraph, toggle)
            assert await notes.evaluate('el => el.scrollWidth <= el.clientWidth + 1')
            await notes.screenshot(path=str(OUT / f'readable-notes-{width}.png'))
            await button.focus()
            await h.page.keyboard.press('Enter')
            await expect(button).to_have_attribute('aria-expanded', 'true')
            await expect(evidence.locator('blockquote')).to_have_text(guide['notes'][0]['source']['excerpt'])
            await expect(evidence.get_by_role('link', name='근거 문서 열기')).to_have_attribute('href', 'https://example.org/notice.pdf')
            assert await notes.evaluate('el => el.scrollWidth <= el.clientWidth + 1')
            await notes.screenshot(path=str(OUT / f'readable-notes-open-{width}.png'))
            await h.page.keyboard.press('Space')
            await expect(evidence).to_be_hidden()
        await button.click()
        await expect(evidence).to_be_visible()
        await h.page.locator('.document-row').filter(has_text='상세 갱신 공고 2').evaluate('el => el.click()')
        await h.wait_request(2, 1)
        await expect(button).to_have_attribute('aria-expanded', 'false')
        await expect(evidence).to_be_hidden()
    finally:
        await h.close()


async def structured_submission_documents(browser):
    import json
    h = await refresh.Harness(browser).start()
    try:
        data = ready()
        fixture = refresh.os.environ.get('SUBMISSION_DOCUMENT_CHECK_FIXTURE')
        if fixture:
            guide = json.loads(Path(fixture).read_text(encoding='utf-8'))
        else:
            source = dict(origin='ATTACHMENT', attachmentName='초격차_공고.hwpx', sectionTitle='제출 서류',
                          excerpt='해당시\n기업신용평가등급 확인서\n신청‧접수기간 내 평가된 확인서\n민간기관')
            guide = dict(documents=[dict(title='기업신용평가등급 확인서', documentNames=['기업신용평가등급 확인서'],
                detail='신청·접수 기간 안에 평가받은 확인서를 준비해 주세요.', requirementLevel='CONDITIONAL', stage='APPLICATION',
                conditions=['제출 대상: 민간기관'], formats=[], source=source)], notes=[])
        data['title'] = '2027년 초격차 스타트업 프로젝트 정부 첫 실증·구매 프로젝트 주관기관 모집공고'
        data['submissionGuide'] = guide
        data['attachments'] = [dict(fileName='초격차_공고.hwpx', fileExtension='hwpx', fileSize=1024,
            parseStatus='COMPLETED', downloadUrl='https://example.org/startup.hwpx')]
        h.states[1] = data
        h.states[2] = dict(data, detectionId=2)
        await h.open(analysis_tab=False)
        table = h.page.get_by_role('table', name='제출서류 안내')
        await expect(table.locator('.business-summary__document-row')).to_have_count(len(guide['documents']))
        conditional = table.get_by_role('button', name='기업신용평가등급 확인서 해당 시 제출 조건 보기', exact=True)
        condition = table.get_by_text('제출 대상: 민간기관', exact=True)
        evidence = table.get_by_role('button', name='기업신용평가등급 확인서 근거 보기', exact=True)
        await expect(condition).to_be_hidden()
        await expect(table.locator('blockquote')).to_have_count(0)
        if fixture:
            await expect(table.locator('.business-summary__document-name')).to_have_text([
                '제출 공문', '사업계획서', '증빙자료', '발표자료', '기업신용평가등급 확인서'])
            await expect(table.get_by_role('button', name='증빙자료 근거 보기', exact=True)).to_be_visible()
            presentation = table.get_by_role('button', name='발표자료 제출 조건 조건 보기', exact=True)
            await presentation.click()
            await expect(table.get_by_text('발표자료를 제출하지 않은 기관은 제출한 사업계획서로 발표합니다.', exact=True)).to_be_visible()
            await presentation.click()
        for width in (1440, 768, 390, 320):
            await h.page.set_viewport_size(dict(width=width, height=1100))
            await expect(conditional).to_have_attribute('aria-expanded', 'false')
            assert await table.evaluate('el => el.scrollWidth <= el.clientWidth + 1'), width
            assert await table.locator('th, td').evaluate_all('cells => cells.every(el => el.scrollWidth <= el.clientWidth + 1)'), width
            await table.screenshot(path=str(OUT / f'documents-redesign-{width}.png'))
            await conditional.focus()
            await h.page.keyboard.press('Enter')
            await expect(condition).to_be_visible()
            await expect(evidence).to_have_attribute('aria-expanded', 'false')
            await evidence.click()
            await expect(table.locator('blockquote')).to_have_text(guide['documents'][-1]['source']['excerpt'])
            await expect(table.get_by_role('link', name='근거 문서 열기')).to_have_attribute('href', 'https://example.org/startup.hwpx')
            assert await table.evaluate('el => el.scrollWidth <= el.clientWidth + 1'), width
            await table.screenshot(path=str(OUT / f'documents-redesign-open-{width}.png'))
            await evidence.click()
            await conditional.focus()
            await h.page.keyboard.press('Space')
            await expect(condition).to_be_hidden()
        await evidence.click()
        await conditional.click()
        await h.page.set_viewport_size(dict(width=1440, height=1100))
        await h.page.locator('.document-row').filter(has_text='상세 갱신 공고 2').evaluate('el => el.click()')
        await h.wait_request(2, 1)
        await expect(conditional).to_have_attribute('aria-expanded', 'false')
        await expect(evidence).to_have_attribute('aria-expanded', 'false')
        await expect(table.locator('blockquote')).to_have_count(0)
        await expect(condition).to_be_hidden()
    finally:
        await h.close()


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel=refresh.os.environ.get('BROWSER_CHANNEL', 'msedge'))
        try:
            await table_and_tabs(browser)
            await structured_submission_documents(browser)
            await compact_organizations_and_amounts(browser)
            await compact_missing_organization(browser)
            await readable_submission_notes(browser)
            await legacy_and_pending(browser)
            await organization_roles_without_analysis(browser)
            await submission_guide_without_analysis(browser)
            await official_submission_fixtures(browser)
        finally:
            await browser.close()
    print('PASS: structured document names, colored requirement/stage labels, independent condition/evidence disclosures, startup fixture, compact missing-organization disclosure, inline keyboard-accessible organization evidence, exact readable won amounts, removed redundant labels, summary fields, qualifiers, grounded documents, source links, four tabs, 1440/768/390/320px, legacy, live refresh and per-notice organization roles, independent submission guide, notes, conditional types and empty-guide switching')


if __name__ == '__main__':
    asyncio.run(main())
