package com.acme.kb.p1.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

@Component
@Order(1)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(TraceIds.HEADER);
        String traceId = (incoming != null && SAFE.matcher(incoming).matches()) ? incoming : TraceIds.newOpaque();
        String requestId = TraceIds.newOpaque();
        req.setAttribute(TraceIds.ATTR_TRACE_ID, traceId);
        req.setAttribute(TraceIds.ATTR_REQUEST_ID, requestId);
        res.setHeader(TraceIds.HEADER, traceId);
        res.setHeader("X-Request-Id", requestId);
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        try {
            TraceIds.putInMdc(traceId);
            chain.doFilter(req, res);
        } finally {
            MDC.remove(TraceIds.MDC_TRACE);
            MDC.remove(TraceIds.MDC_ACTOR);
        }
    }
}
