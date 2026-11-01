package com.acme.kb.p1.security;

import com.acme.kb.p1.config.KbProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.io.IOException;

/**
 * P1 安全边界：
 * - 同源 Cookie 会话 + CSRF（Cookie-to-header），不使用 Bearer 透传；
 * - pilot 必须走 OIDC；开发自动化测试可显式打开 dev subject（关闭 CSRF，仅本地）；
 * - 401/403 返回统一 ApiError，不跳转 HTML，避免 SPA 拿到登录页 HTML；
 * - CSP 与 frame deny 由网关之外再兜一层。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiFilterChain(HttpSecurity http, KbProperties props,
                                             LoginAuditHandler loginAudit) throws Exception {
        boolean dev = props.auth().devSubjectEnabled();
        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
            auth.requestMatchers("/api/v1/health").permitAll();
            auth.requestMatchers("/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/auth/me").permitAll();
            auth.requestMatchers("/login/**", "/oauth2/**", "/error").permitAll();
            if (dev) {
                auth.anyRequest().permitAll();
            } else {
                auth.anyRequest().authenticated();
            }
        });

        http.sessionManagement(session -> session.sessionFixation().migrateSession());

        http.csrf(csrf -> {
            if (dev) {
                csrf.disable();
            } else {
                CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
                repo.setCookieName(props.session().csrfCookie());
                repo.setHeaderName(props.session().csrfHeader());
                CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
                handler.setCsrfRequestAttributeName(null);
                csrf.csrfTokenRepository(repo).csrfTokenRequestHandler(handler);
            }
        });

        http.headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                + "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'")));

        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint(
                        (req, res, e) -> writeError(res, 401, "UNAUTHENTICATED", "需要重新登录"))
                // CSRF 拒绝与越权拒绝必须分开：前者的客户端动作是"重取 token 再试"，
                // 后者是"停止"。混成一个码会让会话过期看起来像权限被收回。
                .accessDeniedHandler((req, res, e) -> {
                    if (e instanceof CsrfException) {
                        writeError(res, 403, "CSRF_INVALID", "校验令牌已过期，请重新加载页面");
                    } else {
                        writeError(res, 403, "ACCESS_DENIED", "身份或权限不足");
                    }
                }));

        if (!dev) {
            if (props.security().oidcIssuer() == null || props.security().oidcIssuer().isBlank()) {
                // 非开发模式没有 OIDC 就没有合法登录入口，宁可启动失败也不留后门
                throw new IllegalStateException("kb.security.oidc-issuer 未配置：pilot 必须走 OIDC 登录");
            }
            // 登录成功/失败都过审计闸门：审计不可用则不签发会话（#4 第 2 条）
            http.oauth2Login(oauth -> oauth
                    .loginPage("/api/v1/auth/login")
                    .successHandler(loginAudit)
                    .failureHandler(loginAudit));
            // 登出由 AuthController 处理：先写审计、失败则保持会话（fail-close）。
            // 不在这里声明 logoutUrl，避免 LogoutFilter 抢先拦截。
        }
        return http.build();
    }

    /**
     * 内网 OIDC：用 issuer 发现文档构造客户端。发现失败即启动失败（fail-close），
     * 不允许在 IdP 不可用时退化为“先放行再说”。只有真正配置了 issuer 才构造，空字符串不触发发现。
     */
    @Bean
    @ConditionalOnExpression("'${kb.security.oidc-issuer:}' != ''")
    public ClientRegistrationRepository clientRegistrationRepository(KbProperties props) {
        ClientRegistration base = ClientRegistrations.fromIssuerLocation(props.security().oidcIssuer())
                .registrationId("kb")
                .build();
        String[] scopes = (props.security().oidcScopes() == null
                || props.security().oidcScopes().isBlank())
                ? new String[]{"openid"}
                : props.security().oidcScopes().split(",");
        ClientRegistration registration = ClientRegistration.withClientRegistration(base)
                .clientId(props.security().oidcClientId())
                .clientSecret(props.security().oidcClientSecret())
                .redirectUri(props.security().oidcRedirectUri())
                .scope(scopes)
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    private static void writeError(HttpServletResponse res, int status, String code,
                                   String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
