package com.publicmonitor.backend.domain.telegram.entity;

import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "telegram_deliveries", uniqueConstraints =
        @UniqueConstraint(name = "uk_telegram_delivery_target", columnNames = {"report_id", "chat_id"}))
@SequenceGenerator(name = "telegram_deliveries_generator", sequenceName = "telegram_deliveries_sequence", allocationSize = 1)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TelegramDelivery extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "telegram_deliveries_generator")
    @Column(name = "delivery_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private MonitoringReport report;
    @Column(name = "chat_id", nullable = false, length = 100)
    private String chatId;
    @Column(name = "recipient_name", length = 100)
    private String recipientName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private TelegramDeliveryState status = TelegramDeliveryState.PENDING;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "attempted_at")
    private LocalDateTime attemptedAt;
    @Column(name = "sent_at")
    private LocalDateTime sentAt;
    @Column(name = "message_id")
    private Long messageId;
    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    public static TelegramDelivery pending(MonitoringReport report, TelegramRecipient recipient) {
        var delivery = new TelegramDelivery();
        delivery.report = report;
        delivery.chatId = recipient.getChatId();
        delivery.recipientName = recipient.getName();
        return delivery;
    }
    public void begin(LocalDateTime at) { attemptCount++; attemptedAt = at; errorMessage = null; }
    public void complete(long messageId, LocalDateTime at) {
        this.messageId = messageId; sentAt = at; status = TelegramDeliveryState.SENT; errorMessage = null;
    }
    public void fail(String reason) { status = TelegramDeliveryState.FAILED; errorMessage = reason; }
}
