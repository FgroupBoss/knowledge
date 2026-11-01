package com.acme.kb.p1.security;

import com.acme.kb.p1.audit.AuditService;
import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.auth.IdentityService;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * OIDC 登录的审计闸门（#4 第 2 条）：登录成功先写审计再放行；
 * 审计写失败则销毁刚建立的会话并返回 503，避免“登录成功但没有留痕”。
 * 登录失败同样落一条无主体的审计事件（tenant 为空时不进哈希链）。
 */
@Component
public class LoginAuditHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final IdentityService identities;
    private final AuditService audit;

    public LoginAuditHandler(IdentityService identities, AuditService audit) {
        this.identities = identities;
        this.audit = audit;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        Actor actor;
        String issuer;
        try {
            if (!(authentication.getPrincipal() instanceof OidcUser oidc)) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "身份类型不受支持");
            }
            issuer = oidc.getClaims().get("iss") == null ? null : String.valueOf(oidc.getClaims().get("iss"));
            actor = identities.resolve(issuer, oidc.getSubject());
            HttpSession session = request.getSession(false);
            audit.recordIndependent(actor, "auth.login", "session",
                    session == null ? null : session.getId(), "ALLOW", null,
                    Map.of("issuer", issuer == null ? "" : issuer));
        } catch (RuntimeException e) {
            // 审计或身份映射失败：不签发会话，返回统一错误
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            writeError(response, e instanceof ApiException api
                    ? api : new ApiException(ErrorCode.AUDIT_WRITE_FAILED, "登录审计不可用"));
            return;
        }
        response.sendRedirect("/");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        try {
            audit.recordBestEffort(null, "auth.login_failed", "session", null, "DENY",
                    "AUTHENTICATION_FAILED", Map.of("reason", exception.getClass().getSimpleName()));
        } catch (RuntimeException ignored) {
            // 失败审计是尽力而为：不放宽认证，也不掩盖原始 401
        }
        writeError(response, new ApiException(ErrorCode.UNAUTHENTICATED, "登录失败"));
    }

    private static void writeError(HttpServletResponse response, ApiException error) throws IOException {
        response.setStatus(error.code().status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + error.code().name()
                + "\",\"message\":\"登录未完成，请稍后重试\"}");
    }
}
