package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.web.dto.SimilarNoticeResponse;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Lazy
@RequiredArgsConstructor
public class SimilarNoticeService {

    private static final int MAX_RESULTS = 3;
    private static final Set<String> GENERIC_TOPIC_TERMS = Set.of(
            "공고", "사업", "지원", "지원사업", "프로그램", "과제", "대상", "기업", "기관",
            "시행", "시행계획", "계획", "신청", "참여", "모집", "선정", "정부", "산업",
            "기술", "개발", "구축", "혁신", "사업화", "연구개발", "중소기업", "중견기업",
            "연도", "년도", "신규", "하반기", "상반기", "ai",
            "접수", "안내", "통합", "위한", "통한", "수행", "목적", "내용", "확인", "합니다", "용역"
    );
    private static final Set<String> GENERIC_TOPIC_PREFIXES = Set.of(
            "공고", "사업", "지원", "프로그램", "과제", "대상", "신청", "참여", "모집", "선정"
    );
    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "((?:총\\s*)?(?:최대\\s*)?[0-9][0-9,.]*\\s*(?:억|천만|백만)?\\s*원)"
    );
    private static final Pattern PURPOSE_HEADING = Pattern.compile(
            "^(?:[ⅠⅡⅢIVX0-9.\\- ]*)사업\\s*목적(?:\\s*[:：])?\\s*(.*)$"
    );
    private static final Pattern NEXT_SECTION_HEADING = Pattern.compile(
            "^(?:[ⅠⅡⅢIVX0-9.\\- ]*)?(?:사업\\s*(?:내용|구분)|지원\\s*(?:규모|내용|분야|대상)|사업비|추진\\s*(?:방향|체계)|신청\\s*자격).*$"
    );
    private static final Pattern INLINE_PURPOSE_SECTION = Pattern.compile(
            "(?:[0-9]+[.\\-]?\\s*)?사업\\s*목적\\s*(?:[○ㅇ□●▪▶※*\\-]+\\s*)?(.+?)(?=(?:[0-9]+[.\\-]?\\s*)?(?:사업\\s*(?:내용|구분)|지원\\s*(?:규모|내용|분야|대상)|사업비|추진\\s*(?:방향|체계)))"
    );
    private static final Pattern PARTNER_CONDITION = Pattern.compile(
            "([^.!?]{0,180}(?:컨소시엄\\s*(?:을\\s*)?구성|파트너\\s*(?:기관)?\\s*(?:필수|참여|확보)|공동연구개발기관\\s*(?:참여\\s*)?필수)[^.!?]{0,180}[.!?]?)"
    );

    private final DocumentDetectionRepository detectionRepository;
    private final DocumentAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;

    private volatile SearchSnapshot searchSnapshot;

    private record SearchEntry(Long versionId, Long documentId, String model,
            double[] vector, Set<String> title, Set<String> purpose) {}
    private record SearchSnapshot(String revision, long builtAt, List<SearchEntry> entries) {}

    private List<SearchEntry> searchEntries() {
        var revision = analysisRepository.similarityRevision();
        String signature = revision == null ? null
                : revision.getTotal() + ":" + revision.getLastId() + ":" + revision.getChangedAt();
        SearchSnapshot cached = searchSnapshot;
        if (signature != null && cached != null && signature.equals(cached.revision())
                && System.nanoTime() - cached.builtAt() < 60_000_000_000L) return cached.entries();
        return rebuildSearchEntries(signature);
    }

    private synchronized List<SearchEntry> rebuildSearchEntries(String signature) {
        SearchSnapshot cached = searchSnapshot;
        if (signature != null && cached != null && signature.equals(cached.revision())
                && System.nanoTime() - cached.builtAt() < 60_000_000_000L) return cached.entries();
        var entries = new ArrayList<SearchEntry>();
        for (DocumentAnalysis analysis : analysisRepository.findLatestSimilarityCandidates(0L)) {
            var version = analysis.getDocumentVersion();
            String organization = version.getDocument().getMonitoringSource().getOrganizationName();
            entries.add(new SearchEntry(version.getId(), version.getDocument().getId(),
                    analysis.getEmbeddingModelName(), unitVector(embedding(analysis)),
                    Set.copyOf(topicTerms(version.getTitle(), organization)),
                    Set.copyOf(topicTerms(purpose(version.getContentText() == null ? "" : version.getContentText(), analysis), organization))));
        }
        List<SearchEntry> snapshot = List.copyOf(entries);
        // Cache derived values only, never JPA entities or legal/source documents.
        searchSnapshot = signature != null && entries.size() <= 5000
                ? new SearchSnapshot(signature, System.nanoTime(), snapshot) : null;
        return snapshot;
    }

    @Transactional(readOnly = true)
    public SimilarNoticeResponse find(Long detectionId) {
        DocumentDetection detection = detectionRepository.findById(detectionId)
                .orElseThrow(() -> new DocumentDetectionException(DocumentDetectionResponseCode.NOT_FOUND));
        DocumentVersion currentVersion = detection.getDocumentVersion();
        DocumentAnalysis currentAnalysis = analysisRepository.findByDocumentVersionId(currentVersion.getId())
                .orElse(null);
        SimilarNoticeResponse.ComparisonSide currentSide = side(currentVersion, currentAnalysis);
        if (currentAnalysis == null) {
            return new SimilarNoticeResponse(currentSide, List.of());
        }
        double[] currentEmbedding = unitVector(embedding(currentAnalysis));
        String organization = currentVersion.getDocument().getMonitoringSource().getOrganizationName();
        Set<String> queryTitle = topicTerms(currentVersion.getTitle(), organization);
        Set<String> queryPurpose = topicTerms(purpose(currentVersion.getContentText() == null ? "" : currentVersion.getContentText(), currentAnalysis), organization);
        Set<String> queryTerms = new LinkedHashSet<>(queryTitle);
        queryTerms.addAll(queryPurpose);
        var inputs = new ArrayList<HybridNoticeRanker.Candidate>();
        var scored = new java.util.HashMap<Long, ScoredCandidate>();
        for (SearchEntry candidate : searchEntries()) {
            if (candidate.documentId().equals(currentVersion.getDocument().getId())) continue;
            boolean comparable = currentEmbedding.length > 0
                    && candidate.vector().length == currentEmbedding.length
                    && currentAnalysis.getEmbeddingModelName() != null
                    && !currentAnalysis.getEmbeddingModelName().isBlank()
                    && currentAnalysis.getEmbeddingModelName().equals(candidate.model());
            double similarity = comparable ? dot(currentEmbedding, candidate.vector()) : Double.NaN;
            Set<String> candidateTerms = new LinkedHashSet<>(candidate.title());
            candidateTerms.addAll(candidate.purpose());
            List<String> sharedTopics = sharedTopicTerms(queryTerms, candidateTerms);
            inputs.add(new HybridNoticeRanker.Candidate(candidate.versionId(), similarity,
                    candidate.title(), candidate.purpose(), sharedTopics));
            scored.put(candidate.versionId(), new ScoredCandidate(candidate.versionId(), similarity, sharedTopics));
        }

        List<SimilarNoticeResponse.SimilarNotice> matches = HybridNoticeRanker.rank(queryTitle, queryPurpose, inputs).stream()
                .flatMap(match -> toResponse(currentAnalysis, scored.get(match.id()), match.basis()).stream())
                .limit(MAX_RESULTS)
                .toList();
        return new SimilarNoticeResponse(currentSide, matches);
    }

    private Optional<SimilarNoticeResponse.SimilarNotice> toResponse(
            DocumentAnalysis currentAnalysis,
            ScoredCandidate scored, String matchBasis
    ) {
        DocumentAnalysis candidateAnalysis = analysisRepository.findByDocumentVersionId(scored.versionId()).orElse(null);
        if (candidateAnalysis == null) return Optional.empty();
        DocumentVersion candidateVersion = candidateAnalysis.getDocumentVersion();
        return detectionRepository
                .findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(candidateVersion.getId())
                .map(latestDetection -> new SimilarNoticeResponse.SimilarNotice(
                        latestDetection.getId(),
                        Double.isFinite(scored.similarity()) ? (int) Math.round(scored.similarity() * 100) : null,
                        candidateVersion.getTitle(),
                        candidateVersion.getDocument().getOriginalUrl(),
                        side(candidateVersion, candidateAnalysis),
                        legalReview(currentAnalysis, candidateAnalysis, scored.sharedTopics()),
                        matchBasis
                ));
    }

    private SimilarNoticeResponse.LegalReview legalReview(
            DocumentAnalysis currentAnalysis,
            DocumentAnalysis candidateAnalysis,
            List<String> sharedTopics
    ) {
        List<SimilarNoticeResponse.LegalRiskCheck> checks = List.of(
                legalRiskCheck("DUPLICATE_SUPPORT", "중복지원", currentAnalysis, candidateAnalysis),
                legalRiskCheck("COST_DOUBLE_COUNTING", "사업비·인건비 중복계상", currentAnalysis, candidateAnalysis),
                legalRiskCheck("RESULT_IP_REUSE", "성과물·지식재산 재사용", currentAnalysis, candidateAnalysis),
                legalRiskCheck("CONFIDENTIALITY", "비밀정보·영업비밀", currentAnalysis, candidateAnalysis),
                legalRiskCheck("PROPOSAL_TEXT_REUSE", "제안서 문장·자료 재사용", currentAnalysis, candidateAnalysis)
        );
        List<String> restrictedLabels = checks.stream()
                .filter(check -> check.status().equals("RESTRICTION_FOUND"))
                .map(SimilarNoticeResponse.LegalRiskCheck::label)
                .toList();
        String topic = topicPhrase(sharedTopics);
        String summary = restrictedLabels.isEmpty()
                ? "두 공고의 " + topic + " 관련 제한 조항과 자료 확인 상태를 아래에서 확인하세요. 조항 미발견은 제한 없음이나 동시 신청 가능을 뜻하지 않습니다."
                : "두 공고의 " + topic + " 관련 원문 중 "
                        + String.join(", ", restrictedLabels) + " 항목의 제한 조항을 발견했습니다. 실제 신청 과제·비용·성과물의 중복 여부는 확인되지 않아 두 사업의 충돌을 확정할 수 없습니다.";
        return new SimilarNoticeResponse.LegalReview(
                restrictedLabels.isEmpty() ? "REVIEW_REQUIRED" : "RESTRICTION_FOUND", summary, checks,
                "공고 원문 기반의 사전 위험 점검이며 법률 자문이 아닙니다. 최종 신청 전 공고 담당기관과 법무·재무 담당자의 확인이 필요합니다."
        );
    }

    private SimilarNoticeResponse.LegalRiskCheck legalRiskCheck(
            String type, String label, DocumentAnalysis currentAnalysis,
            DocumentAnalysis candidateAnalysis
    ) {
        LegalFinding current = legalFinding(currentAnalysis, type);
        LegalFinding candidate = legalFinding(candidateAnalysis, type);
        boolean restrictionFound = "RESTRICTION_FOUND".equals(current.status())
                || "RESTRICTION_FOUND".equals(candidate.status());
        String finding = "현재 공고: " + current.summary() + " 유사 공고: " + candidate.summary();
        List<String> evidenceParts = new ArrayList<>();
        if (!current.evidence().isBlank()) evidenceParts.add("현재 공고: “" + current.evidence() + "”");
        if (!candidate.evidence().isBlank()) evidenceParts.add("유사 공고: “" + candidate.evidence() + "”");
        List<SimilarNoticeResponse.VerifiedLegalEvidence> verifiedEvidence = new ArrayList<>();
        if (Set.of("RESTRICTION_FOUND", "CAUTION").contains(current.status()) && !current.evidence().isBlank()) {
            verifiedEvidence.add(new SimilarNoticeResponse.VerifiedLegalEvidence("현재 공고", current.summary(), current.evidence()));
        }
        if (Set.of("RESTRICTION_FOUND", "CAUTION").contains(candidate.status()) && !candidate.evidence().isBlank()) {
            verifiedEvidence.add(new SimilarNoticeResponse.VerifiedLegalEvidence("유사 공고", candidate.summary(), candidate.evidence()));
        }
        return new SimilarNoticeResponse.LegalRiskCheck(
                type, label, restrictionFound ? "RESTRICTION_FOUND"
                        : "ASSESSMENT_INCOMPLETE".equals(current.status()) || "ASSESSMENT_INCOMPLETE".equals(candidate.status())
                        ? "ASSESSMENT_INCOMPLETE"
                        : "DATA_INSUFFICIENT".equals(current.status()) || "DATA_INSUFFICIENT".equals(candidate.status())
                        ? "DATA_INSUFFICIENT"
                        : "NOT_FOUND".equals(current.status()) && "NOT_FOUND".equals(candidate.status())
                        ? "NOT_FOUND" : "REVIEW_REQUIRED", finding,
                String.join(" ", evidenceParts), List.copyOf(verifiedEvidence)
        );
    }

    private String topicPhrase(List<String> sharedTopics) {
        List<String> topics = sharedTopics.stream().limit(3).toList();
        return topics.isEmpty() ? "공통 사업" : String.join("·", topics);
    }
    private LegalFinding legalFinding(DocumentAnalysis analysis, String type) {
        if (analysis == null || analysis.getComparisonSummary() == null) return LegalFinding.missing();
        try {
            JsonNode risks = objectMapper.readTree(analysis.getComparisonSummary()).path("legalRisks");
            if (risks.isArray()) {
                for (JsonNode risk : risks) {
                    if (!type.equals(risk.path("type").asText())) continue;
                    String status = risk.path("status").asText();
                    String summary = risk.path("summary").asText().strip();
                    String evidence = risk.path("evidenceExcerpt").asText().strip();
                    if (!summary.isBlank()) return new LegalFinding(status, summary, evidence);
                }
            }
        } catch (RuntimeException ignored) {
            // 과거 또는 손상된 분석 데이터는 안전하게 추가 확인 상태로 처리합니다.
        }
        return LegalFinding.missing();
    }

    private List<String> sharedTopicTerms(Set<String> current, Set<String> candidate) {
        Set<String> shared = new LinkedHashSet<>();
        for (String currentTerm : current) {
            for (String candidateTerm : candidate) {
                String common = sharedTopicTerm(currentTerm, candidateTerm);
                if (common != null) shared.add(common);
                if (shared.size() == 5) return List.copyOf(shared);
            }
        }
        return List.copyOf(shared);
    }

    private String sharedTopicTerm(String left, String right) {
        if (left.equals(right)) return left;
        if (left.length() < 4 || right.length() < 4) return null;
        if (left.contains(right)) return right;
        if (right.contains(left)) return left;
        return null;
    }

    private Set<String> topicTerms(String value, String organization) {
        String source = (organization == null || organization.isBlank()
                ? value
                : value.replace(organization, " ")).toLowerCase(Locale.ROOT);
        source = Normalizer.normalize(source, Normalizer.Form.NFKC)
                .replaceAll("인공\\s*지능|artificial\\s+intelligence", "ai")
                .replaceAll("예지\\s*보전|예측\\s*정비", "예지보전")
                .replaceAll("결함\\s*탐지|불량\\s*검출", "결함탐지")
                .replaceAll("스마트\\s*팩토리|스마트\\s*공장|지능형\\s*공장", "스마트공장")
                .replaceAll("국제\\s*공동\\s*연구|해외\\s*공동\\s*연구", "국제공동연구");
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : source.split("[^0-9a-z가-힣]+")) {
            String term = canonicalTopicTerm(stripParticle(raw));
            if (term.length() >= 2 && !term.matches("[0-9]+(?:년|년도|차|회)?")
                    && !isGenericTopicTerm(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private String stripParticle(String term) {
        for (String suffix : List.of("에서는", "으로", "에서", "에게", "와", "과", "을", "를", "은", "는", "의")) {
            if (term.endsWith(suffix) && term.length() - suffix.length() >= 2) {
                return term.substring(0, term.length() - suffix.length());
            }
        }
        return term;
    }

    private String canonicalTopicTerm(String term) {
        return switch (term) {
            case "인공지능" -> "ai";
            case "샌드박스" -> "규제샌드박스";
            default -> term;
        };
    }

    private boolean isGenericTopicTerm(String term) {
        return GENERIC_TOPIC_TERMS.contains(term)
                || GENERIC_TOPIC_PREFIXES.stream().anyMatch(term::startsWith);
    }

    private SimilarNoticeResponse.ComparisonSide side(DocumentVersion version, DocumentAnalysis analysis) {
        String content = version.getContentText() == null ? "" : version.getContentText();
        ComparisonFields comparison = comparisonFields(analysis);
        return new SimilarNoticeResponse.ComparisonSide(
                version.getDocument().getMonitoringSource().getOrganizationName(),
                comparison == null ? purpose(content, analysis) : comparison.purpose(),
                comparison == null
                        ? firstMatch(MONEY_PATTERN, content, "원문에서 지원 규모를 확인하지 못했습니다.")
                        : comparison.supportScale(),
                comparison == null
                        ? proposalValue(analysis, "applicationDeadline", "원문에서 접수 마감을 확인하지 못했습니다.")
                        : comparison.applicationDeadline(),
                comparison == null ? eligibility(analysis) : comparison.eligibility(),
                comparison == null ? requiredPartner(content) : comparison.requiredPartner()
        );
    }

    private ComparisonFields comparisonFields(DocumentAnalysis analysis) {
        if (analysis == null || analysis.getComparisonSummary() == null) return null;
        try {
            JsonNode summary = objectMapper.readTree(analysis.getComparisonSummary());
            String purpose = summary.path("purpose").asText().strip();
            String supportScale = summary.path("supportScale").asText().strip();
            String applicationDeadline = summary.path("applicationDeadline").asText().strip();
            String eligibility = summary.path("eligibility").asText().strip();
            String requiredPartner = summary.path("requiredPartner").asText().strip();
            if (purpose.isBlank() || supportScale.isBlank() || applicationDeadline.isBlank()
                    || eligibility.isBlank() || requiredPartner.isBlank()) {
                return null;
            }
            return new ComparisonFields(
                    purpose, supportScale, applicationDeadline, eligibility, requiredPartner
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String purpose(String content, DocumentAnalysis analysis) {
        if (analysis != null && analysis.getComparisonSummary() != null) {
            try {
                String savedPurpose = objectMapper.readTree(analysis.getComparisonSummary()).path("purpose").asText().strip();
                if (!savedPurpose.isBlank()) return savedPurpose;
            } catch (RuntimeException ignored) {
                // Fall back to the source when legacy comparison JSON cannot be read.
            }
        }
        Matcher inlineSection = INLINE_PURPOSE_SECTION.matcher(normalizeText(content));
        if (inlineSection.find()) return cleanSourceLine(inlineSection.group(1));

        List<String> purposeLines = new ArrayList<>();
        boolean collecting = false;
        for (String rawLine : content.split("\\R")) {
            String line = normalizeText(rawLine);
            if (line.isBlank()) continue;
            if (!collecting) {
                Matcher heading = PURPOSE_HEADING.matcher(line);
                if (!heading.matches()) continue;
                collecting = true;
                String inlinePurpose = cleanSourceLine(heading.group(1));
                if (!inlinePurpose.isBlank()) purposeLines.add(inlinePurpose);
                continue;
            }
            if (NEXT_SECTION_HEADING.matcher(line).matches() || line.matches("^[0-9]+[.)]$")) break;
            String cleaned = cleanSourceLine(line);
            if (!cleaned.isBlank()) purposeLines.add(cleaned);
        }
        if (!purposeLines.isEmpty()) return String.join(" ", purposeLines);
        return analysis == null ? "사업 목적을 확인하지 못했습니다." : normalizeText(analysis.getSummary());
    }

    private String requiredPartner(String content) {
        Matcher condition = PARTNER_CONDITION.matcher(normalizeText(content));
        if (condition.find()) return cleanSourceLine(condition.group(1));

        String best = "";
        int bestScore = 0;
        for (String rawLine : content.split("\\R")) {
            String line = cleanSourceLine(normalizeText(rawLine));
            if (line.isBlank() || line.length() > 500) continue;
            int score = 0;
            if (line.contains("컨소시엄")) score += 4;
            if (line.contains("파트너")) score += 4;
            if (line.matches(".*(?:공동연구개발기관|공동기관|참여기관).*")) score += 2;
            if (line.matches(".*(?:필수|구성|참여해야|참여하여야).*")) score += 2;
            if (score > bestScore) {
                best = line;
                bestScore = score;
            }
        }
        return bestScore >= 4 ? best : "원문에서 필수 파트너 조건을 확인하지 못했습니다.";
    }

    private String cleanSourceLine(String value) {
        return value
                .strip()
                .replaceFirst("^[○ㅇ□●▪▶※*\\-]+\\s*", "")
                .replaceFirst("^(?:[0-9]+[.\\-]?\\s*)?사업\\s*내용\\s*", "")
                .strip();
    }

    private String eligibility(DocumentAnalysis analysis) {
        if (analysis == null || analysis.getProposalDirection() == null) {
            return "신청 자격을 확인하지 못했습니다.";
        }
        try {
            JsonNode items = objectMapper.readTree(analysis.getProposalDirection())
                    .path("preparation").path("eligibilityChecklist");
            List<String> titles = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (!item.path("title").asText().isBlank()) titles.add(item.path("title").asText());
                    if (titles.size() == 2) break;
                }
            }
            return titles.isEmpty() ? "신청 자격을 확인하지 못했습니다." : String.join(", ", titles);
        } catch (RuntimeException exception) {
            return "신청 자격을 확인하지 못했습니다.";
        }
    }

    private String proposalValue(DocumentAnalysis analysis, String field, String fallback) {
        if (analysis == null || analysis.getProposalDirection() == null) return fallback;
        try {
            String value = objectMapper.readTree(analysis.getProposalDirection())
                    .path("preparation").path(field).asText();
            return value.isBlank() ? fallback : value;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private List<Double> embedding(DocumentAnalysis analysis) {
        if (analysis.getSimilarityEmbedding() == null) return List.of();
        try {
            JsonNode values = objectMapper.readTree(analysis.getSimilarityEmbedding());
            if (!values.isArray()) return List.of();
            List<Double> result = new ArrayList<>();
            boolean nonzero = false;
            for (JsonNode value : values) {
                if (!value.isNumber()) return List.of();
                double number = value.asDouble();
                if (!Double.isFinite(number)) return List.of();
                nonzero |= number != 0;
                result.add(number);
            }
            if (!nonzero) return List.of();
            return result;
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private double[] unitVector(List<Double> values) {
        double scale = values.stream().mapToDouble(Math::abs).max().orElse(0);
        if (scale == 0) return new double[0];
        double[] vector = new double[values.size()];
        double norm = 0;
        for (int i = 0; i < vector.length; i++) {
            vector[i] = values.get(i) / scale;
            norm += vector[i] * vector[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }

    private double dot(double[] left, double[] right) {
        double value = 0;
        for (int i = 0; i < left.length; i++) value += left[i] * right[i];
        return Math.max(-1, Math.min(1, value));
    }

    private String firstMatch(Pattern pattern, String value, String fallback) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).strip() : fallback;
    }

    private String normalizeText(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private record ScoredCandidate(
            Long versionId,
            double similarity,
            List<String> sharedTopics
    ) {
    }

    private record LegalFinding(String status, String summary, String evidence) {
        private static LegalFinding missing() {
            return new LegalFinding("ASSESSMENT_INCOMPLETE", "저장된 분석이 없거나 읽을 수 없어 판정이 미완료입니다.", "");
        }
    }
    private record ComparisonFields(
            String purpose,
            String supportScale,
            String applicationDeadline,
            String eligibility,
            String requiredPartner
    ) {
    }
}
