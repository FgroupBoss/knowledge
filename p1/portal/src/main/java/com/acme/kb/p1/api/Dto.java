package com.acme.kb.p1.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 与 contracts/openapi.yaml 的 components/schemas 一一对应的响应/请求载荷。 */
public final class Dto {

    private Dto() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UploadAccepted(String documentId, String taskId, String operationId, String status,
                                 boolean duplicate, int version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskView(String id, String documentId, String operationId, String taskType, String status,
                           int attempts, int maxAttempts, Instant nextRunAt, Instant leaseUntil,
                           String errorCode, String errorSummary, Instant startedAt, Instant finishedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentView(String id, String tenantId, String workspaceId, String kbId,
                              String displayName, int version, String status, String sha256,
                              long sizeBytes, String mimeType, String classification, String engineDocId,
                              TaskView latestTask, Instant createdAt, Instant updatedAt,
                              String errorCode, String errorSummary) {
    }

    public record ChatRequestDto(String question, String conversationId, List<String> knowledgeBaseIds,
                                 Integer topK, Integer contextChunks) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatResponse(String requestId, String conversationId, String messageId, String status,
                               String answer, List<CitationView> citations, Usage usage, Latency latency,
                               AclStats aclStats, String traceId) {
    }

    public record Usage(Integer promptTokens, Integer completionTokens) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConversationView(String id, String title, String status, Instant createdAt,
                                   Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageView(String id, String conversationId, String requestId, String role,
                              String content, String status, Instant createdAt,
                              List<CitationView> citations) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FeedbackRequest(String rating, String reasonCode, String comment) {
    }

    public record Latency(long authorizationMs, long retrievalMs, Long firstTokenMs, long totalMs) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AclStats(int requestedKbCount, int authorizedKbCount, int engineCandidates,
                           int droppedByAcl, int droppedByClearance, int droppedByStaleVersion,
                           int droppedByUnsupportedCitation, int promptChunks, List<String> droppedScopes) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CitationView(String citationId, String documentId, int documentVersion, String engineDocId,
                               String engineChunkId, String title, Integer page, String section,
                               Integer charStart, Integer charEnd, String snippet, String snippetDigest,
                               double score, boolean aclVerified) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Principal(String tenantCode, String userCode, String clearance, List<WorkspaceView> workspaces) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceView(String id, String code, String name, String role,
                                List<KnowledgeBaseView> knowledgeBases) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KnowledgeBaseView(String id, String workspaceId, String code, String name,
                                   String classification, long aclVersion, String engineKbId, String status) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Backlog(int outboxUnpublished, long outboxOldestAgeSeconds,
                          Map<String, Integer> tasksByStatus, KafkaLag kafka, KafkaLag deleteKafka,
                          int dlqCount,
                          int unconvergedDocuments, long engineChunks, long searchableEngineDocs,
                          Map<String, Long> cacheStats) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KafkaLag(String consumerGroup, int partitions, long currentOffset, long endOffset, long lag) {
    }

    // ------------------------------------------------------------ P2 Wiki 治理（openapi-1.2.yaml）

    /** 与 `components/schemas/WikiPage` 对齐；`aclVersionGuard` 是契约响应名，库里列名 `acl_version`（ADR-010）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiPageView(String id, String workspaceId, String kbId, String slug, String title,
                               String status, String freshness, String ownerUserId, int currentRevisionNo,
                               Integer publishedRevisionNo, long aclVersionGuard, Instant validUntil,
                               Instant reviewAt, Instant createdAt, Instant updatedAt, String body,
                               String bodyDigest, List<String> capabilities, List<WikiSourceView> sources,
                               WikiSyncTaskView syncTask) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiPageList(List<WikiPageView> items, String nextCursor) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiRevisionSummaryView(String id, String pageId, int revisionNo, String status,
                                          String authorUserId, String reviewerUserId, Instant reviewedAt,
                                          String bodyDigest, String summary, Instant createdAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiRevisionList(List<WikiRevisionSummaryView> items, String nextCursor) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiRevisionView(String id, String pageId, int revisionNo, String status,
                                   String authorUserId, String reviewerUserId, Instant reviewedAt,
                                   String bodyDigest, String summary, Instant createdAt, String body,
                                   String parentRevisionId, List<WikiSourceView> sources) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiSourceView(String id, String revisionId, String sourceType, String sourceId,
                                 Integer sourceVersion, Integer chunkIndex, String locator,
                                 String contentDigest, long aclVersion, boolean available) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiReviewQueueItem(String revisionId, String pageId, String title, int revisionNo,
                                      String authorUserId, Instant submittedAt, Instant slaDueAt,
                                      boolean overdue) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiReviewQueue(List<WikiReviewQueueItem> items, String nextCursor) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiDiffLine(String op, String text) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiDiffView(String pageId, int fromRevisionNo, int toRevisionNo, String fromBodyDigest,
                               String toBodyDigest, int addedLines, int removedLines, boolean truncated,
                               List<WikiDiffLine> lines) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiSyncTaskView(String id, String operation, String status, int attempts, int maxAttempts,
                                   Instant nextRunAt, String errorCode, String note) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiPageCreateRequest(String slug, String title, String body, String summary,
                                        String ownerUserId, Instant validUntil, Instant reviewAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiDraftRequest(String title, String body, String summary) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiSourceRequest(String sourceType, String sourceId, Integer sourceVersion,
                                    Integer chunkIndex, String locator) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiGovernanceRequest(String ownerUserId, Instant validUntil, Instant reviewAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WikiDecisionRequest(String decisionNote) {
    }
}
