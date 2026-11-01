package com.acme.kb.p1.api;

import com.acme.kb.p1.auth.ActorHolder;
import com.acme.kb.p1.chat.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 会话列表、历史消息与消息反馈；所有权校验在 ConversationService 内完成。 */
@RestController
@RequestMapping("/api/v1")
public class ConversationController {

    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @GetMapping("/conversations")
    public List<Dto.ConversationView> list() {
        return conversations.list(ActorHolder.require());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<Dto.MessageView> messages(@PathVariable String conversationId) {
        return conversations.messages(ActorHolder.require(), conversationId);
    }

    @PostMapping("/messages/{messageId}/feedback")
    public ResponseEntity<Void> feedback(@PathVariable String messageId,
                                         @RequestBody Dto.FeedbackRequest request) {
        conversations.feedback(ActorHolder.require(), messageId, request);
        return ResponseEntity.noContent().build();
    }
}
