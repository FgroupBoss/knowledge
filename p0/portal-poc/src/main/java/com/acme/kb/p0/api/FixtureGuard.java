package com.acme.kb.p0.api;

import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.common.Secrets;
import com.acme.kb.p0.config.P0Properties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * 夹具端点的门禁：必须同时满足"环境显式开启"和"独立口令匹配"。
 * 测试身份头本身不足以调用这些端点，避免把越权夹具留在常规主体的可达面上。
 */
@Component
public class FixtureGuard {

    public static final String TOKEN_HEADER = "X-P0-Fixture-Token";

    private final P0Properties props;
    private final byte[] expected;

    public FixtureGuard(P0Properties props) {
        this.props = props;
        String token = props.fixture().enabled()
                ? Secrets.resolveOrNull(props.fixture().token(), props.fixture().tokenFile())
                : null;
        this.expected = token == null ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    public void require(HttpServletRequest request) {
        if (!props.fixture().enabled()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "夹具端点在当前环境未启用");
        }
        if (expected == null) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "夹具端点缺少口令配置");
        }
        byte[] provided = request.getHeader(TOKEN_HEADER) == null
                ? new byte[0] : request.getHeader(TOKEN_HEADER).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "夹具口令不正确");
        }
    }
}
