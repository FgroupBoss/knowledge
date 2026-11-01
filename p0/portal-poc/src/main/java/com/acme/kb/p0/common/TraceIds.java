package com.acme.kb.p0.common;

import org.slf4j.MDC;

import java.util.UUID;

/** traceId/correlationId 贯穿 API、任务、Kafka header、worker、引擎请求与审计。 */
public final class TraceIds {

    public static final String HEADER = "X-Trace-Id";
    public static final String ATTR_TRACE_ID = "p0.traceId";
    public static final String ATTR_REQUEST_ID = "p0.requestId";
    public static final String MDC_TRACE = "traceId";
    public static final String MDC_ACTOR = "actor";
    public static final String KAFKA_HEADER_TRACE = "p0-trace-id";
    public static final String KAFKA_HEADER_CORRELATION = "p0-correlation-id";
    public static final String KAFKA_HEADER_EVENT_ID = "p0-event-id";
    public static final String KAFKA_HEADER_ACTOR = "p0-actor-id";
    public static final String KAFKA_HEADER_SCHEMA_VERSION = "p0-schema-version";

    private TraceIds() {
    }

    public static String newOpaque() {
        return UUID.randomUUID().toString();
    }

    public static void putInMdc(String traceId) {
        MDC.put(MDC_TRACE, traceId);
    }

    public static String current() {
        String t = MDC.get(MDC_TRACE);
        return t == null ? newOpaque() : t;
    }
}
