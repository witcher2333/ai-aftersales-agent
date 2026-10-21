package dev.portfolio.aftersales;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

record UserRecord(long id, String username, String passwordHash, String role) {}
record OrderRecord(long id, long customerId, String itemName, String sku, long amountCents,
                   String orderStatus, String logisticsStatus, LocalDateTime deliveredAt, LocalDateTime createdAt) {}
record CaseRecord(long id, long orderId, long customerId, String caseType, String reason,
                  String status, long version, Long assignedTo, String idempotencyKey,
                  LocalDateTime createdAt, LocalDateTime updatedAt) {}
record CaseEvent(long id, long caseId, Long actorId, String eventType, String fromStatus,
                 String toStatus, String note, LocalDateTime createdAt) {}
record PolicyRecord(long id, String code, String title, String content, boolean active, LocalDateTime updatedAt) {}
record AnalysisRecord(long id, long caseId, String category, String recommendation,
                      String evidenceJson, String modelName, LocalDateTime createdAt) {}
record AnalysisTask(long id, long caseId, String status, int attempts, LocalDateTime nextAttemptAt,
                    String lastError, LocalDateTime updatedAt) {}
record CaseDetail(CaseRecord ticket, OrderRecord order, List<CaseEvent> events,
                  AnalysisRecord analysis, AnalysisTask analysisTask) {}
record CurrentUser(long id, String username, String role) {}

record CreateCaseRequest(@NotNull Long orderId, @NotBlank String caseType,
                         @NotBlank @Size(max = 1000) String reason,
                         @NotBlank @Size(max = 100) String idempotencyKey) {}
record DecisionRequest(@NotBlank String decision, @NotBlank @Size(max = 1000) String note,
                       @NotNull Long expectedVersion) {}
record PolicyUpdateRequest(@NotBlank @Size(max = 3000) String content,
                           @NotNull Boolean active) {}
