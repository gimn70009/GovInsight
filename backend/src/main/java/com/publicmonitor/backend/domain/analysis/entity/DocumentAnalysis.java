package com.publicmonitor.backend.domain.analysis.entity;

import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "document_analyses",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_analyses_version",
                columnNames = "version_id"
        ),
        check = @CheckConstraint(
                name = "ck_document_analyses_importance",
                constraint = "importance in ('HIGH', 'NORMAL', 'LOW')"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
        name = "document_analyses_sequence_generator",
        sequenceName = "document_analyses_sequence",
        allocationSize = 1
)
public class DocumentAnalysis extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_analyses_sequence_generator")
    @Column(name = "analysis_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private DocumentVersion documentVersion;

    @Column(name = "summary", nullable = false, columnDefinition = "CLOB")
    private String summary;

    @Column(name = "key_points", columnDefinition = "CLOB")
    private String keyPoints;

    @Enumerated(EnumType.STRING)
    @Column(name = "importance", nullable = false, length = 20)
    private DocumentImportance importance;

    @Column(name = "reason", columnDefinition = "CLOB")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility", length = 30)
    private AnalysisEligibility eligibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "favorable_or_not", length = 30)
    private AnalysisFavorability favorableOrNot;

    @Column(name = "proposal_direction", columnDefinition = "CLOB")
    private String proposalDirection;

    @Column(name = "used_tools", columnDefinition = "CLOB")
    private String usedTools;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;

    private DocumentAnalysis(
            DocumentVersion documentVersion,
            String summary,
            String keyPoints,
            DocumentImportance importance,
            String reason,
            AnalysisEligibility eligibility,
            AnalysisFavorability favorableOrNot,
            String proposalDirection,
            String usedTools,
            String modelName,
            LocalDateTime analyzedAt
    ) {
        this.documentVersion = documentVersion;
        this.summary = summary;
        this.keyPoints = keyPoints;
        this.importance = importance;
        this.reason = reason;
        this.eligibility = eligibility;
        this.favorableOrNot = favorableOrNot;
        this.proposalDirection = proposalDirection;
        this.usedTools = usedTools;
        this.modelName = modelName;
        this.analyzedAt = analyzedAt;
    }

    public static DocumentAnalysis create(
            DocumentVersion documentVersion,
            String summary,
            String keyPoints,
            DocumentImportance importance,
            String reason,
            AnalysisEligibility eligibility,
            AnalysisFavorability favorableOrNot,
            String proposalDirection,
            String usedTools,
            String modelName,
            LocalDateTime analyzedAt
    ) {
        return new DocumentAnalysis(
                documentVersion,
                summary,
                keyPoints,
                importance,
                reason,
                eligibility,
                favorableOrNot,
                proposalDirection,
                usedTools,
                modelName,
                analyzedAt
        );
    }
}
