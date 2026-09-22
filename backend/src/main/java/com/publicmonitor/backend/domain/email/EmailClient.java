package com.publicmonitor.backend.domain.email;
import lombok.RequiredArgsConstructor;
import com.publicmonitor.backend.domain.report.ReportEmailTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class EmailClient {
    private final JavaMailSender reportMailSender;
    private final EmailProperties properties;
    public void send(String address, String title, String body) {
        if (!properties.configured()) throw new EmailClientException("메일 발신 계정을 먼저 설정해 주세요.");
        try {
            var message = reportMailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setValidateAddresses(true); helper.setFrom(properties.username(), properties.senderName()); helper.setTo(address);
            String subject = title == null ? "GovInsight 보고서" : title.replaceAll("[\\r\\n]", " ");
            String text = body == null ? "" : body;
            helper.setSubject(subject); helper.setText(text, html(subject, text)); reportMailSender.send(message);
        } catch (MailAuthenticationException exception) {
            throw new EmailClientException("메일 계정 인증에 실패했습니다. 앱 비밀번호와 SMTP 설정을 확인해 주세요.");
        } catch (MailException exception) {
            throw new EmailClientException("메일 전송 결과를 확인하지 못했습니다. 수신함과 주소를 확인한 뒤 재전송해 주세요.");
        } catch (jakarta.mail.MessagingException | java.io.UnsupportedEncodingException exception) {
            throw new EmailClientException("메일 주소 또는 발신자 설정을 확인해 주세요.");
        }
    }
    static String html(String title, String body) {
        return ReportEmailTemplate.render(title, body);
    }
}
