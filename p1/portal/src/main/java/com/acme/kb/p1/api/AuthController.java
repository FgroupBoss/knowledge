package com.acme.kb.p1.api;

import com.acme.kb.p1.audit.AuditService;
import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.auth.ActorHolder;
import com.acme.kb.p1.auth.IdentityService;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.config.KbProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 登录/登出/当前主体。登录本身交给 Spring Security 的 OIDC 过滤器，这里只做入口与登出。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final KbProperties props;
    private final IdentityService identities;
    private final AuditService audit;

    public AuthController(KbProperties props, IdentityService identities, AuditService audit) {
        this.props = props;
        this.identities = identities;
        this.audit = audit;
    }

    @GetMapping("/login")
    public void login(HttpServletResponse response) throws IOException {
        if (props.auth().devSubjectEnabled()) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        response.sendRedirect("/oauth2/authorization/kb");
    }

    /**
     * 登出先写审计再销毁会话：审计不可用则返回 503 且保持登录（fail-close），
     * 不能让“会话已销毁但没有任何登出痕迹”。未登录时按幂等 204 处理。
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Actor actor = currentActor();
        String sessionId = request.getSession(false) == null ? null : request.getSession(false).getId();
        if (actor != null) {
            audit.recordIndependent(actor, "auth.logout", "session", sessionId, "ALLOW", null,
                    Map.of("devSubject", props.auth().devSubjectEnabled()));
        }
        new SecurityContextLogoutHandler().logout(request, response, null);
        Cookie cookie = new Cookie(props.session().cookieName(), "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        response.addCookie(cookie);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Actor actor = ActorHolder.currentOrNull();
        Map<String, Object> body = new LinkedHashMap<>();
        if (actor == null) {
            body.put("authenticated", false);
            return body;
        }
        body.put("authenticated", true);
        body.put("userId", actor.userId());
        body.put("tenantId", actor.tenantId());
        body.put("userCode", actor.userCode());
        body.put("displayName", actor.displayName());
        body.put("clearance", actor.clearance().name());
        HttpSession session = request.getSession(false);
        body.put("sessionId", session == null ? null : session.getId());
        return body;
    }

    private Actor currentActor() {
        Actor actor = ActorHolder.currentOrNull();
        if (actor != null) {
            return actor;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof OidcUser oidc)) {
            return null;
        }
        try {
            Object issuer = oidc.getClaims().get("iss");
            return identities.resolve(issuer == null ? null : String.valueOf(issuer), oidc.getSubject());
        } catch (ApiException e) {
            return null;
        }
    }
}
