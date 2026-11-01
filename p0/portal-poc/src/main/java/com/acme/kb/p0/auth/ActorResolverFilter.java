package com.acme.kb.p0.auth;

import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.common.TraceIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 主体解析：P0 用受控测试身份头，P1 换成 OIDC 会话，两者之后所有授权判定都在服务端。
 * 未解析出主体的请求一律 401，绝不按“无租户”继续执行。
 */
@Component
@Order(2)
public class ActorResolverFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-P0-Subject";

    private final IdentityService identity;
    private final ObjectMapper mapper;
    private final boolean stubEnabled;

    public ActorResolverFilter(IdentityService identity, ObjectMapper mapper,
                               @Value("${p0.auth.stub-enabled:true}") boolean stubEnabled) {
        this.identity = identity;
        this.mapper = mapper;
        this.stubEnabled = stubEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/v1/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            Actor actor;
            if (stubEnabled) {
                actor = identity.resolveTestIdentity(req.getHeader(HEADER));
            } else {
                String bearer = req.getHeader("Authorization");
                if (bearer == null || !bearer.startsWith("Bearer ")) {
                    throw new ApiException(ErrorCode.UNAUTHENTICATED, "缺少有效身份");
                }
                String[] pair = bearer.substring(7).split(":", 2);
                actor = pair.length == 2 ? identity.resolve(pair[0], pair[1])
                        : identity.resolve(IdentityService.TEST_ISSUER, pair[0]);
            }
            ActorHolder.set(actor);
            MDC.put(TraceIds.MDC_ACTOR, actor.key());
            chain.doFilter(req, res);
        } catch (ApiException e) {
            writeError(res, e);
        } finally {
            ActorHolder.clear();
            MDC.remove(TraceIds.MDC_ACTOR);
        }
    }

    private void writeError(HttpServletResponse res, ApiException e) throws IOException {
        res.setStatus(e.code().status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        Object traceId = res.getHeader(TraceIds.HEADER);
        Map<String, Object> body = Map.of(
                "code", e.code().name(),
                "message", e.getMessage(),
                "requestId", String.valueOf(res.getHeader("X-Request-Id")),
                "traceId", traceId == null ? "" : String.valueOf(traceId));
        mapper.writeValue(res.getOutputStream(), body);
    }
}
