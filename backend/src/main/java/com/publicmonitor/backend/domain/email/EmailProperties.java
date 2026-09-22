package com.publicmonitor.backend.domain.email;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties(prefix = "app.email")
public record EmailProperties(@DefaultValue("GMAIL") Provider provider, @DefaultValue("") String username,
        @DefaultValue("") String password, @DefaultValue("GovInsight") String senderName) {
    public enum Provider { GMAIL, NAVER }
    public boolean configured() { return !username.isBlank() && !password.isBlank(); }
    public String host() { return provider == Provider.NAVER ? "smtp.naver.com" : "smtp.gmail.com"; }
    @Override public String toString() { return "EmailProperties[provider=" + provider + ", configured=" + configured() + "]"; }
}
