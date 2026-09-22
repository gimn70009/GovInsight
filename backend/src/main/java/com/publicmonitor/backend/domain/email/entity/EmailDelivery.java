package com.publicmonitor.backend.domain.email.entity;

import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "email_deliveries", uniqueConstraints =
        @UniqueConstraint(name = "uk_email_delivery_target", columnNames = {"report_id", "email_address"}))
@SequenceGenerator(name = "email_deliveries_generator", sequenceName = "email_deliveries_sequence", allocationSize = 1)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailDelivery extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "email_deliveries_generator")
    @Column(name = "delivery_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private MonitoringReport report;
    @Column(name = "email_address", nullable = false, length = 254)
    private String address;
    @Column(name = "recipient_name", length = 100)
    private String recipientName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private EmailDeliveryState status = EmailDeliveryState.PENDING;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "attempted_at")
    private LocalDateTime attemptedAt;
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    public static EmailDelivery pending(MonitoringReport report, EmailRecipient recipient) {
        var delivery = new EmailDelivery();
        delivery.report = report;
        delivery.address = recipient.getAddress();
        delivery.recipientName = recipient.getName();
        return delivery;
    }
    public void begin(LocalDateTime at) { attemptCount++; attemptedAt = at; errorMessage = null; }
    public void complete(LocalDateTime at) {
        sentAt = at; status = EmailDeliveryState.SENT; errorMessage = null;
    }
    public void fail(String reason) { status = EmailDeliveryState.FAILED; errorMessage = reason; }
}
