package com.publicmonitor.backend.domain.email;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
class EmailClientTest {
    private EmailProperties properties(EmailProperties.Provider provider) { return new EmailProperties(provider, "sender@gmail.com", "private-secret", "GovInsight"); }
    @Test void gmailAndNaverUseAuthenticatedTlsWithTimeouts() {
        for (var provider : EmailProperties.Provider.values()) {
            var config = properties(provider);
            var sender = new EmailConfig().reportMailSender(config);
            assertThat(sender.getHost()).isEqualTo(provider == EmailProperties.Provider.GMAIL ? "smtp.gmail.com" : "smtp.naver.com");
            assertThat(sender.getPort()).isEqualTo(587);
            assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.starttls.required", "true").containsEntry("mail.smtp.ssl.checkserveridentity", "true").containsEntry("mail.smtp.timeout", "10000");
            assertThat(config.toString()).doesNotContain("private-secret", "sender@gmail.com");
        }
    }
    @Test void sendsOneRecipientAndEscapesHtmlWithPlainTextAlternative() throws Exception {
        var sender = mock(JavaMailSender.class);
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        new EmailClient(sender, properties(EmailProperties.Provider.GMAIL)).send("recipient@naver.com", "보고서\r\n제목", "<script>alert(1)</script>\n보고서 & 본문");
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("recipient@naver.com");
        assertThat(message.getSubject()).isEqualTo("보고서  제목");
        message.saveChanges();
        var bytes = new java.io.ByteArrayOutputStream(); message.writeTo(bytes);
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("multipart/alternative", "text/plain", "text/html");
        assertThat(EmailClient.html("<title>", "<script>\n&본문")).contains("&lt;title&gt;", "&lt;script&gt;<br>&amp;본문").doesNotContain("<script>");
        verify(sender).send(message);
    }
    @Test void namedDownloadLinksAreClickableInHtml() {
        assertThat(EmailClient.html("보고서", "• [신청서](https://example.org/f?a=1&b=2)"))
            .contains("href=\"https://example.org/f?a=1&amp;b=2\">신청서</a>");
    }
    @Test void authenticationErrorsDoNotExposeProviderCredentials() {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailAuthenticationException("private-secret server diagnostic")).when(sender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> new EmailClient(sender, properties(EmailProperties.Provider.NAVER)).send("person@gmail.com", "제목", "본문"))
            .isInstanceOf(EmailClientException.class).hasMessageContaining("인증에 실패").hasMessageNotContaining("private-secret");
    }
    @Test void missingCredentialsNeverSend() {
        var sender = mock(JavaMailSender.class);
        assertThatThrownBy(() -> new EmailClient(sender, new EmailProperties(EmailProperties.Provider.GMAIL, "", "", "GovInsight")).send("a@naver.com", "제목", "본문")).isInstanceOf(EmailClientException.class);
        verifyNoInteractions(sender);
    }

    @Test void expandsDatesInBothMimeAlternativesWithoutSendingRealMail() throws Exception {
        var sender = mock(JavaMailSender.class);
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        new EmailClient(sender, properties(EmailProperties.Provider.GMAIL))
                .send("recipient@example.org", "’27년 보고서", "’27.3월 제출 / (’23~’25)");
        message.saveChanges();

        assertThat(message.getSubject()).isEqualTo("2027년 보고서");
        var texts = new java.util.HashMap<String, String>();
        collectText(message, texts);
        assertThat(texts.get("text/plain")).isEqualTo("2027년 3월 제출 / (2023~2025)");
        assertThat(texts.get("text/html")).contains("2027년 3월 제출 / (2023~2025)");
        verify(sender).send(message);
    }

    private void collectText(jakarta.mail.Part part, java.util.Map<String, String> texts) throws Exception {
        if (part.isMimeType("multipart/*")) {
            var multipart = (jakarta.mail.Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) collectText(multipart.getBodyPart(i), texts);
        } else if (part.isMimeType("text/plain")) {
            texts.put("text/plain", (String) part.getContent());
        } else if (part.isMimeType("text/html")) {
            texts.put("text/html", (String) part.getContent());
        }
    }
}
