"""Run with ai/.venv/Scripts/python.exe against a frontend preview on port 4173.
All application API requests are replaced; no real messages or credentials are used.
"""
import asyncio
import json
import os
from pathlib import Path
from playwright.async_api import async_playwright, expect

REPORT_BODY = (Path(os.environ['DELIVERY_REPORT_BODY']).read_text(encoding='utf-8') if os.getenv('DELIVERY_REPORT_BODY') else '공공기관 모니터링 보고서\n첨부파일: [신청서.hwp](https://example.go.kr/file?id=1&part=2)')

BASE = os.getenv('DELIVERY_PREVIEW_URL', 'http://127.0.0.1:4173')
OUT = Path(os.getenv('DELIVERY_SCREENSHOT_DIR', 'frontend/dist/delivery-checks'))

async def main():
    email = dict(version=0, enabled=True, configured=True, provider='GMAIL', senderAddress='reports@gmail.com', senderName='GovInsight', updatedAt=None,
        recipients=[dict(name='사업 담당자', address='business@gmail.com', enabled=True), dict(name='기획팀', address='planning@naver.com', enabled=True)])
    telegram = dict(version=0, enabled=True, botConfigured=True, updatedAt=None, recipients=[dict(name='운영 담당자', chatId='123456789', enabled=True)])
    report = dict(reportId=1, runId=7, title='[공공기관 모니터링] 9월 22일 보고서', createdAt='2026-09-22T08:00:00', generatedAt='2026-09-22T08:00:00',
        telegram=dict(status='SENT',recipientCount=1,sentCount=1,failedCount=0,errorMessage=None), email=dict(status='PARTIAL',recipientCount=2,sentCount=1,failedCount=1,errorMessage=None))
    delivery = dict(deliveryId=12,address='planning@naver.com',name='기획팀',status='FAILED',attemptCount=1,attemptedAt='2026-09-22T08:00:00',sentAt=None,errorMessage='메일 서버 연결 실패')
    calls=[]; failures=[]; reject_save=False; reject_refresh=False
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(viewport=dict(width=1440,height=1100),device_scale_factor=1)
        page.on('pageerror',lambda e: failures.append(str(e)))
        await page.add_init_script("sessionStorage.setItem('govinsight.accessToken','browser-test-placeholder')")
        async def route_api(route):
            nonlocal email, reject_save, reject_refresh
            request=route.request; path=request.url.split('/api/')[1]; data=None
            calls.append((request.method,path,request.post_data))
            if path=='email/settings':
                if request.method=='GET' and reject_refresh:
                    reject_refresh=False
                    return await route.fulfill(status=503,json=dict(isSuccess=False,message='설정 조회 실패 테스트'))
                if request.method=='PUT':
                    if reject_save:
                        reject_save=False
                        return await route.fulfill(status=409,json=dict(isSuccess=False,message='설정 저장 실패 테스트'))
                    payload=request.post_data_json
                    addresses=[r['address'] for r in payload['recipients']]
                    if len(addresses)!=len(set(addresses)):
                        return await route.fulfill(status=400,json=dict(isSuccess=False,message='같은 이메일 주소는 한 번만 등록할 수 있습니다.'))
                    assert payload['version']==email['version']
                    email.update(payload);email['version']+=1
                data=email
            elif path=='telegram/settings':
                if request.method=='PUT':
                    if reject_save:
                        reject_save=False
                        return await route.fulfill(status=409,json=dict(isSuccess=False,message='설정 저장 실패 테스트'))
                    payload=request.post_data_json
                    assert payload['version']==telegram['version']
                    telegram.update(payload);telegram['version']+=1
                data=telegram
            elif path=='telegram/connection-check':
                assert request.post_data_json==dict(expectedChatId='123456789')
                data=dict(botConnected=True,chatConnected=True,botUsername='mock_bot',message='연결 확인됨')
            elif path=='telegram/test-message':
                assert request.post_data_json==dict(expectedChatId='123456789')
                data=dict(sent=True,message='테스트 메시지를 보냈습니다.')
            elif path.startswith('report-deliveries?'):
                data=dict(content=[report],totalPages=1,totalElements=1,page=0,size=10)
            elif path=='report-deliveries/1':
                data=dict(report=report,body=REPORT_BODY,
                    telegramDeliveries=[dict(deliveryId=1,chatId='123456789',name='운영 담당자',status='SENT',attemptCount=1,attemptedAt=None,sentAt='2026-09-22T08:00:00',errorMessage=None)],
                    emailDeliveries=[dict(deliveryId=11,address='business@gmail.com',name='사업 담당자',status='SENT',attemptCount=1,attemptedAt=None,sentAt='2026-09-22T08:00:00',errorMessage=None),delivery])
            elif path=='email/test-message':
                assert request.post_data_json==dict(expectedAddress='business@gmail.com')
                data=dict(sent=True,message='테스트 메일을 보냈습니다.')
            elif path=='email/deliveries/12/retry':
                assert request.post_data_json==dict(expectedAddress='planning@naver.com',expectedAttemptCount=1)
                delivery.update(status='SENT',attemptCount=2,errorMessage=None,sentAt='2026-09-22T09:00:00')
                report['email'].update(status='SENT',sentCount=2,failedCount=0)
                data=delivery
            else:
                raise AssertionError('Unexpected API: '+path)
            await route.fulfill(json=dict(isSuccess=True,data=data,message='OK'))
        await page.route('**/api/**',route_api)
        await page.goto(BASE+'/telegram')
        await expect(page).to_have_url(BASE+'/reports')
        await expect(page.get_by_role('tab',name='텔레그램',exact=False)).to_have_attribute('aria-selected','true')
        await page.get_by_role('tab',name='텔레그램',exact=True).focus()
        await page.keyboard.press('ArrowRight')
        await expect(page.get_by_role('tab',name='이메일',exact=True)).to_be_focused()
        await expect(page.get_by_role('tab',name='이메일',exact=True)).to_have_attribute('aria-selected','true')
        await page.keyboard.press('Home')
        await expect(page.get_by_role('tab',name='텔레그램',exact=True)).to_be_focused()
        OUT.mkdir(parents=True,exist_ok=True)
        tg=page.locator('#delivery-panel-telegram')
        await expect(tg.get_by_role('heading',name='자동 발송',exact=True)).to_be_visible()
        await expect(tg.get_by_role('switch')).to_have_count(1)
        choice=tg.get_by_role('checkbox',name='운영 담당자 수신',exact=True)
        await choice.click();await expect(choice).not_to_be_checked()
        await choice.click();await expect(choice).to_be_checked()
        tg_toggle=tg.get_by_role('switch',name='텔레그램 보고서 발송',exact=True)
        reject_save=True
        await tg_toggle.click()
        await expect(tg.get_by_text('설정 저장 실패 테스트')).to_be_visible()
        await expect(tg_toggle).to_have_attribute('aria-checked','true')
        await tg_toggle.click();await expect(tg_toggle).to_have_attribute('aria-checked','false')
        await page.reload();await expect(tg_toggle).to_have_attribute('aria-checked','false')
        await tg_toggle.click();await expect(tg_toggle).to_have_attribute('aria-checked','true')
        if await page.locator('.toast button').count(): await page.locator('.toast button').click()
        await page.locator('.delivery-channels').screenshot(path=str(OUT/'unified-telegram-desktop.png'))
        await tg.get_by_role('button',name='운영 담당자 연결 확인',exact=True).click()
        await expect(tg.get_by_text('연결 확인됨',exact=True)).to_be_visible()
        await tg.get_by_role('button',name='운영 담당자 테스트 발송',exact=True).click()
        test_dialog=page.get_by_role('dialog',name='테스트 발송',exact=True)
        await test_dialog.get_by_role('button',name='보내기',exact=True).click()
        await expect(test_dialog).not_to_be_visible()
        await tg.get_by_role('button',name='발송 설정',exact=True).click()
        tg_setup=page.get_by_role('dialog',name='텔레그램 발송 설정',exact=True)
        await tg_setup.get_by_text('수신자 등록 방법',exact=True).click()
        await expect(tg_setup.get_by_role('button',name='링크 복사',exact=True)).to_be_visible()
        await tg_setup.get_by_role('button',name='완료',exact=True).click()
        if await page.locator('.toast button').count(): await page.locator('.toast button').click()
        await page.set_viewport_size(dict(width=390,height=844))
        await page.locator('.delivery-channels').screenshot(path=str(OUT/'unified-telegram-mobile.png'))
        assert await page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Telegram mobile overflow'
        await page.set_viewport_size(dict(width=1440,height=1100))
        await page.get_by_role('tab',name='이메일',exact=False).click()
        await expect(page.get_by_text('business@gmail.com',exact=True)).to_be_visible()
        await expect(page.locator('#delivery-panel-email').get_by_role('switch')).to_have_count(1)
        recipient_choice=page.get_by_role('checkbox',name='사업 담당자 수신',exact=True)
        await recipient_choice.click(); await expect(recipient_choice).not_to_be_checked()
        assert not email['recipients'][0]['enabled']
        await recipient_choice.click(); await expect(recipient_choice).to_be_checked()
        assert email['recipients'][0]['enabled']
        OUT.mkdir(parents=True,exist_ok=True)
        await page.screenshot(path=str(OUT/'report-delivery-desktop.png'),full_page=True)
        await page.locator('.delivery-channels').screenshot(path=str(OUT/'unified-email-desktop.png'))
        panel=page.locator('#delivery-panel-email')
        await panel.get_by_role('button',name='수신자 추가',exact=True).click()
        dialog=page.get_by_role('dialog',name='이메일 수신자 추가',exact=True)
        await dialog.get_by_label('이름',exact=True).fill('신규 담당자')
        await dialog.get_by_label('이메일 주소',exact=True).fill('new@naver.com')
        await dialog.get_by_role('button',name='저장',exact=True).click()
        await expect(dialog).not_to_be_visible()
        await expect(panel.get_by_text('new@naver.com',exact=True)).to_be_visible()
        await panel.get_by_role('button',name='신규 담당자 수정',exact=True).click()
        dialog=page.get_by_role('dialog',name='이메일 수신자 수정',exact=True)
        await dialog.get_by_label('이름',exact=True).fill('수정 담당자')
        await dialog.get_by_role('button',name='저장',exact=True).click()
        await expect(panel.get_by_text('수정 담당자',exact=True)).to_be_visible()
        await panel.get_by_role('button',name='수신자 추가',exact=True).click()
        dialog=page.get_by_role('dialog',name='이메일 수신자 추가',exact=True)
        await dialog.get_by_label('이메일 주소',exact=True).fill('business@gmail.com')
        await dialog.get_by_role('button',name='저장',exact=True).click()
        await expect(dialog.get_by_text('같은 이메일 주소는 한 번만 등록할 수 있습니다.')).to_be_visible()
        await dialog.get_by_role('button',name='취소',exact=True).click()
        reject_save=True
        toggle=panel.get_by_role('switch',name='이메일 보고서 발송',exact=True)
        await toggle.click()
        await expect(panel.get_by_text('설정 저장 실패 테스트')).to_be_visible()
        await expect(toggle).to_have_attribute('aria-checked','true')
        await toggle.click();await expect(toggle).to_have_attribute('aria-checked','false')
        await page.reload(); await page.get_by_role('tab',name='이메일',exact=False).click()
        await expect(toggle).to_have_attribute('aria-checked','false')
        await toggle.click();await expect(toggle).to_have_attribute('aria-checked','true')
        await panel.get_by_role('button',name='사업 담당자 테스트 발송',exact=True).click()
        dialog=page.get_by_role('dialog',name='테스트 메일 발송',exact=True)
        await dialog.get_by_role('button',name='보내기',exact=True).click();await expect(dialog).not_to_be_visible()
        await panel.get_by_role('button',name='수정 담당자 삭제',exact=True).click()
        dialog=page.get_by_role('dialog',name='수신자 삭제',exact=True)
        await dialog.get_by_role('button',name='삭제',exact=True).click();await expect(panel.get_by_text('new@naver.com',exact=True)).to_have_count(0)
        await page.locator('.delivery-report-row').click()
        dialog=page.get_by_role('dialog',name='보고서 발송 상세',exact=True)
        await expect(dialog.get_by_text('planning@naver.com',exact=False)).to_be_visible()
        await dialog.get_by_text('보고서 내용',exact=True).click()
        report_links=dialog.locator('.report-body a')
        assert await report_links.count() >= 1
        for link in await report_links.all():
            assert (await link.get_attribute('href')).startswith(('https://','http://'))
            await expect(link).to_have_attribute('target','_blank')
        if '제출 준비 서류 ↓' in REPORT_BODY:
            rows=dialog.locator('.report-submission-link')
            await expect(rows).to_have_count(5)
            await expect(dialog.locator('.report-submission-card')).to_have_count(0)
            await expect(rows.first.get_by_role('link',name='사업계획서 (ZIP)')).to_have_attribute('href','https://example.go.kr/download?id=1&seq=2')
            await expect(rows.nth(2)).to_have_text('납세증명서(해당 시)')
            await expect(dialog.locator('.report-body-note')).to_have_count(1)
            await expect(dialog.locator('.report-body-note')).to_have_text('미확인 항목: 제출처·방법, 문의 담당')
            await expect(dialog.locator('.report-body').get_by_text('공고문.pdf',exact=True)).to_have_count(0)
        await dialog.locator('.report-body').screenshot(path=str(OUT/'report-action-brief-desktop.png'))
        await page.set_viewport_size(dict(width=390,height=844))
        assert await dialog.evaluate('(el) => el.scrollWidth <= el.clientWidth'), 'Report body mobile overflow'
        await report_links.last.scroll_into_view_if_needed()
        await dialog.screenshot(path=str(OUT/'report-action-brief-mobile.png'))
        await page.set_viewport_size(dict(width=1440,height=1100))

        await dialog.get_by_role('button',name='재전송',exact=True).click()
        confirmation=page.get_by_role('dialog',name='보고서 재전송',exact=True)
        await confirmation.get_by_role('button',name='다시 보내기',exact=True).click()
        await expect(confirmation).not_to_be_visible();await expect(dialog.get_by_role('button',name='재전송',exact=True)).to_have_count(0)
        await dialog.get_by_role('button',name='닫기',exact=True).click()
        await page.get_by_role('group',name='발송 내역 채널').get_by_role('button',name='이메일',exact=True).click()
        await expect(page.locator('.delivery-report-row .delivery-result')).to_have_count(1)
        assert any('channel=EMAIL' in path for _,path,_ in calls)
        if await page.locator('.toast button').count():
            await page.locator('.toast button').click()
        await page.set_viewport_size(dict(width=390,height=844))
        await page.screenshot(path=str(OUT/'report-delivery-mobile.png'),full_page=True)
        await page.locator('.delivery-channels').screenshot(path=str(OUT/'unified-email-mobile.png'))
        assert await page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Mobile overflow'
        email['configured']=False;email['enabled']=False;email['senderAddress']=''
        await page.reload();await page.get_by_role('tab',name='이메일',exact=False).click()
        toggle=panel.get_by_role('switch',name='이메일 보고서 발송',exact=True)
        await expect(toggle).to_be_enabled()
        await expect(toggle).to_have_attribute('aria-checked','false')
        await expect(panel.get_by_text('보내는 계정을 설정하면 켤 수 있어요.')).to_be_visible()
        await expect(panel.locator('.delivery-setup-notice, .delivery-sender')).to_have_count(0)
        await expect(panel.get_by_role('switch')).to_have_count(1)
        await page.screenshot(path=str(OUT/'email-ux-unconfigured-mobile.png'),full_page=True)
        assert await page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Unconfigured mobile overflow'
        await page.set_viewport_size(dict(width=1440,height=1050))
        await page.screenshot(path=str(OUT/'email-ux-unconfigured-desktop.png'),full_page=True)
        before_enable=sum(method=='PUT' for method,_,_ in calls)
        await toggle.click()
        setup=page.get_by_role('dialog',name='보내는 계정 설정',exact=True)
        await expect(setup).to_be_visible()
        await expect(toggle).to_have_attribute('aria-checked','false')
        assert sum(method=='PUT' for method,_,_ in calls)==before_enable
        await expect(setup.get_by_role('button',name='자동 발송 켜기',exact=True)).to_have_count(0)
        await setup.get_by_role('button',name='설정 상태 다시 확인',exact=True).click()
        await expect(setup.get_by_text('아직 발신 계정이 설정되지 않았어요. 아래 설정 방법을 확인해 주세요.')).to_be_visible()
        await setup.get_by_text('관리자용 설정 방법',exact=True).click()
        await expect(setup.get_by_text('APP_EMAIL_PROVIDER',exact=True)).to_be_visible()
        await page.set_viewport_size(dict(width=390,height=844))
        await page.screenshot(path=str(OUT/'email-ux-setup-mobile.png'),full_page=True)
        assert await setup.evaluate('(el) => el.scrollWidth <= el.clientWidth'), 'Setup dialog overflow'
        reject_refresh=True
        await setup.get_by_role('button',name='설정 상태 다시 확인',exact=True).click()
        await expect(setup.get_by_text('설정 조회 실패 테스트')).to_be_visible()
        await expect(toggle).to_have_attribute('aria-checked','false')
        await setup.get_by_role('button',name='나중에',exact=True).click()
        await expect(setup).not_to_be_visible()
        await panel.get_by_role('button',name='사업 담당자 테스트 발송',exact=True).click()
        await expect(setup).to_be_visible()
        assert sum(path=='email/test-message' for _,path,_ in calls)==1
        email['configured']=True;email['senderAddress']='sender@gmail.com'
        await setup.get_by_role('button',name='설정 상태 다시 확인',exact=True).click()
        await expect(setup.get_by_role('button',name='자동 발송 켜기',exact=True)).to_be_visible()
        await expect(toggle).to_have_attribute('aria-checked','false')
        assert sum(method=='PUT' for method,_,_ in calls)==before_enable
        reject_save=True
        await setup.get_by_role('button',name='자동 발송 켜기',exact=True).click()
        await expect(setup.get_by_text('설정 저장 실패 테스트')).to_be_visible()
        await expect(toggle).to_have_attribute('aria-checked','false')
        await setup.get_by_role('button',name='자동 발송 켜기',exact=True).click()
        await expect(setup).not_to_be_visible()
        await expect(toggle).to_have_attribute('aria-checked','true')
        await page.reload();await page.get_by_role('tab',name='이메일',exact=False).click()
        await expect(toggle).to_have_attribute('aria-checked','true')
        assert not failures, failures
        assert sum(path=='email/test-message' for _,path,_ in calls)==1
        assert sum(path=='email/deliveries/12/retry' for _,path,_ in calls)==1
        # Telegram follows the same onboarding flow without writes until explicitly enabled.
        telegram['botConfigured']=False;telegram['enabled']=False
        await page.reload()
        await expect(tg_toggle).to_be_enabled()
        before_tg=sum(method in ('PUT','POST') and path.startswith('telegram/') for method,path,_ in calls)
        await tg_toggle.click();await expect(tg_setup).to_be_visible()
        await expect(tg_setup.get_by_role('button',name='자동 발송 켜기',exact=True)).to_have_count(0)
        await tg_setup.get_by_role('button',name='설정 상태 다시 확인',exact=True).click()
        await expect(tg_setup.get_by_text('아직 봇이 설정되지 않았어요. 서비스 관리자에게 설정을 요청해 주세요.')).to_be_visible()
        await tg_setup.get_by_role('button',name='나중에',exact=True).click()
        await tg.get_by_role('button',name='운영 담당자 테스트 발송',exact=True).click()
        await expect(tg_setup).to_be_visible()
        telegram['botConfigured']=True
        await tg_setup.get_by_role('button',name='설정 상태 다시 확인',exact=True).click()
        await expect(tg_setup.get_by_role('button',name='자동 발송 켜기',exact=True)).to_be_visible()
        await expect(tg_toggle).to_have_attribute('aria-checked','false')
        assert sum(method in ('PUT','POST') and path.startswith('telegram/') for method,path,_ in calls)==before_tg
        await tg_setup.get_by_role('button',name='자동 발송 켜기',exact=True).click()
        await expect(tg_setup).not_to_be_visible()
        await expect(tg_toggle).to_have_attribute('aria-checked','true')
        assert sum(path=='telegram/test-message' for _,path,_ in calls)==1
        assert sum(path=='telegram/connection-check' for _,path,_ in calls)==1
        assert not failures, failures
        print('PASS: unified Telegram/email controls, Telegram selection/save rollback/persistence/connection/test/setup guidance/explicit activation/mobile, redirect, channel tabs, Gmail/Naver recipients, create/edit/delete, duplicate error, failed-save rollback, reload persistence, test mail, targeted retry, history filter, mobile layout, recipient checkboxes, unconfigured toggle guidance without writes, setup refresh and failure, explicit activation after setup; no browser errors')
        await browser.close()

asyncio.run(main())
