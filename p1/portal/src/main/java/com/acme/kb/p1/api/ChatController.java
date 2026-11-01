package com.acme.kb.p1.api;

import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.auth.ActorHolder;
import com.acme.kb.p1.chat.ChatService;
import com.acme.kb.p1.chat.SseSink;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.common.TraceIds;
import com.acme.kb.p1.config.KbProperties;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 问答端点。流式路径显式把 Actor 与 traceId 带进工作线程：ActorHolder 只在 servlet 线程有效，
 * worker 线程读取它必然为空，因此这里不可能"顺手"拿到别人的身份。
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final ChatService chat;
    private final KbProperties props;
    private final ExecutorService executor;

    public ChatController(ChatService chat, KbProperties props, ExecutorService sseExecutor) {
        this.chat = chat;
        this.props = props;
        this.executor = sseExecutor;
    }

    @PostMapping(value = "/knowledge-bases/{kbId}/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public Dto.ChatResponse chat(@PathVariable String kbId,
                                 @RequestBody Dto.ChatRequestDto request,
                                 @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        Actor actor = ActorHolder.require();
        return chat.ask(actor, kbId, request, idempotencyKey);
    }

    @PostMapping(value = "/knowledge-bases/{kbId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String kbId,
                             @RequestBody Dto.ChatRequestDto request,
                             @RequestHeader(name = "Idempotency-Key", required = false)
                             String idempotencyKey) {
        Actor actor = ActorHolder.require();
        String traceId = TraceIds.current();
        long timeoutMs = props.ollama().timeoutMs() + 30_000L;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        Sequenced sink = new Sequenced(emitter);
        executor.execute(() -> {
            MDC.put(TraceIds.MDC_TRACE, traceId);
            MDC.put(TraceIds.MDC_ACTOR, actor.key());
            try {
                chat.stream(actor, kbId, request, idempotencyKey, sink::token, sink);
                emitter.complete();
            } catch (ApiException e) {
                sink.finishError(e.code().name(), e.getMessage(), traceId);
                emitter.complete();
            } catch (RuntimeException e) {
                sink.finishError(ErrorCode.INTERNAL_ERROR.name(), "服务暂时不可用", traceId);
                emitter.complete();
            } finally {
                MDC.remove(TraceIds.MDC_TRACE);
                MDC.remove(TraceIds.MDC_ACTOR);
            }
        });
        return emitter;
    }

    /** 取消生成：仅请求所有者；客户端断开时服务端也会走同一路径（#9）。 */
    @PostMapping("/chat/completions/{requestId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String requestId) {
        chat.cancel(ActorHolder.require(), requestId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 事件序号与"错误帧只发一次"的执行者。发送失败（客户端断开）只记状态不再抛出，
     * 也不再补发后续帧；本次生成仍会跑完并落库，因为 P0 没有将中断信号传给模型侧的通道。
     */
    private static final class Sequenced implements SseSink {

        private final SseEmitter emitter;
        private final AtomicInteger seq = new AtomicInteger();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicBoolean disconnectHandled = new AtomicBoolean();
        private volatile Runnable onDisconnect;

        private Sequenced(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onDisconnect(Runnable cancelAction) {
            this.onDisconnect = cancelAction;
        }

        @Override
        public void meta(String requestId, String conversationId, String messageId) {
            send("meta", Map.of("requestId", requestId, "conversationId", conversationId,
                    "messageId", messageId));
        }

        @Override
        public void sources(List<Dto.CitationView> citations) {
            send("sources", Map.of("citations", citations));
        }

        @Override
        public void token(String delta) {
            send("token", Map.of("text", delta));
        }

        @Override
        public void done(Dto.ChatResponse response) {
            send("done", response);
        }

        @Override
        public void error(String code, String message, String traceId) {
            finishError(code, message, traceId);
        }

        private void finishError(String code, String message, String traceId) {
            if (failed.compareAndSet(false, true)) {
                send("error", Map.of("code", code, "message", message == null ? "" : message,
                        "traceId", traceId));
            }
        }

        private void send(String type, Object payload) {
            if (failed.get() && !"error".equals(type)) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(seq.incrementAndGet())).name(type)
                        .data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                failed.set(true);
                Runnable action = onDisconnect;
                if (action != null && disconnectHandled.compareAndSet(false, true)) {
                    // 客户端已断开：释放生成（#9）；再发帧也不会成功，不再尝试
                    action.run();
                }
            }
        }
    }
}
