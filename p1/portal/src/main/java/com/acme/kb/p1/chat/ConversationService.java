package com.acme.kb.p1.chat;

import com.acme.kb.p1.api.Dto;
import com.acme.kb.p1.audit.AuditService;
import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.repo.ChatMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 会话与消息的读取，以及消息反馈。所有查询都同时校验 tenant_id 与 user_id：
 * 非所有者一律返回 CONVERSATION_NOT_FOUND，不泄露会话是否存在。
 */
@Service
public class ConversationService {

    private static final int MAX_CONVERSATIONS = 100;
    private static final int MAX_MESSAGES = 500;
    private static final Set<String> RATINGS = Set.of("UP", "DOWN");

    private final ChatMapper store;
    private final AuditService audit;

    public ConversationService(ChatMapper store, AuditService audit) {
        this.store = store;
        this.audit = audit;
    }

    public List<Dto.ConversationView> list(Actor actor) {
        List<Dto.ConversationView> views = new ArrayList<>();
        for (ChatMapper.ConversationRow row : store.listConversations(actor.tenantId(), actor.userId(),
                MAX_CONVERSATIONS)) {
            views.add(new Dto.ConversationView(row.id(), row.title(), row.status(), row.createdAt(),
                    row.updatedAt()));
        }
        return views;
    }

    public List<Dto.MessageView> messages(Actor actor, String conversationId) {
        ChatMapper.ConversationRow conversation = ownedConversation(actor, conversationId);
        List<Dto.MessageView> messages = new ArrayList<>();
        for (ChatMapper.MessageRow row : store.listMessages(conversation.id(), MAX_MESSAGES)) {
            List<Dto.CitationView> citations = row.requestId() == null ? List.of()
                    : CitationService.toViews(store.listByRequest(row.requestId()));
            messages.add(new Dto.MessageView(row.id(), row.conversationId(), row.requestId(), row.role(),
                    row.content(), row.status(), row.createdAt(), citations));
        }
        audit.recordIndependent(actor, "conversation.messages", "conversation", conversationId,
                "ALLOW", null, Map.of("messages", messages.size()));
        return messages;
    }

    @Transactional
    public void feedback(Actor actor, String messageId, Dto.FeedbackRequest request) {
        if (request == null || request.rating() == null
                || !RATINGS.contains(request.rating().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "rating 只能是 UP 或 DOWN");
        }
        if (request.comment() != null && request.comment().length() > 1000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "comment 最长为 1000 字符");
        }
        ChatMapper.MessageRow message = store.findMessage(messageId);
        if (message == null || !message.tenantId().equals(actor.tenantId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "消息不存在");
        }
        ChatMapper.ConversationRow conversation = store.findConversation(message.conversationId());
        if (conversation == null || !conversation.userId().equals(actor.userId())
                || !conversation.tenantId().equals(actor.tenantId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "消息不存在");
        }
        if (!"ASSISTANT".equals(message.role())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "只能对助手回答反馈");
        }
        store.upsertFeedback(UUID.randomUUID().toString(), actor.tenantId(), messageId, actor.userId(),
                request.rating().toUpperCase(Locale.ROOT), request.reasonCode(), request.comment(),
                Instant.now());
        audit.recordInTx(actor, "chat.feedback", "chat_message", messageId, "ALLOW", null,
                Map.of("rating", request.rating().toUpperCase(Locale.ROOT)));
    }

    private ChatMapper.ConversationRow ownedConversation(Actor actor, String conversationId) {
        ChatMapper.ConversationRow conversation = store.findConversation(conversationId);
        if (conversation == null || !conversation.tenantId().equals(actor.tenantId())
                || !conversation.userId().equals(actor.userId())) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        return conversation;
    }
}
