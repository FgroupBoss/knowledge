package com.acme.kb.p0.chat;

import com.acme.kb.p0.api.Dto;

import java.util.List;

/**
 * SSE 事件的落地抽象：服务层只产生语义事件，控制器负责把它们写成带递增 seq 的 data 帧。
 */
public interface SseSink {

    void meta(String requestId, String conversationId, String messageId);

    void sources(List<Dto.CitationView> citations);

    void token(String delta);

    void done(Dto.ChatResponse response);

    void error(String code, String message, String traceId);
}
