package com.acme.kb.p1.auth;

import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.common.TraceIds;
import com.acme.kb.p1.config.KbProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 主体解析：P1 只信任两类凭据——
 * 1) Spring Security 已认证的 OIDC 主体（issuer + subject）；
 * 2) 仅本地开发/自动化测试开启的 {@code X-KB-Subject} 测试头（默认关闭，pilot 禁止）。
 * 两者之后都会落到 MySQL 映射出 Actor，客户端提交的 tenant/workspace/kbId 永远不参与身份判定。
 */
@Component
@Order(2)
public class ActorResolverFilter extends OncePerRequestFilter {

    public static final String DEV_HEADER = "X-KB-Subject";

    private final IdentityService identity;
    private final ObjectMapper mapper;
    private final boolean devSubjectEnabled;

    public ActorResolverFilter(IdentityService identity, ObjectMapper mapper, KbProperties props) {
        this.identity = identity;
        this.mapper = mapper;
        this.devSubjectEnabled = props.auth().devSubjectEnabled();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.equals("/api/v1/health")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            Actor actor = resolveFromSecurityContext();
            if (actor == null && devSubjectEnabled) {
                actor = identity.resolveDevIdentity(req.getHeader(DEV_HEADER));
            }
            if (actor == null) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "缺少有效身份");
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

    private Actor resolveFromSecurityContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String issuer = null;
        String subject = null;
        if (auth.getPrincipal() instanceof OidcUser oidc) {
            issuer = stringClaim(oidc.getClaims().get("iss"));
            subject = oidc.getSubject();
        }
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            return null;
        }
        return identity.resolve(issuer, subject);
    }

    private static String stringClaim(Object value) {
        return value == null ? null : String.valueOf(value);
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
