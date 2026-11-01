package com.acme.kb.p1.api;

import com.acme.kb.p1.config.KbProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 供网关/脚本使用的存活探针：不触达数据库、不返回版本与地址。
 * 依赖组件的就绪状态走 /actuator/health/readiness。
 */
@RestController
public class HealthController {

    private final String role;

    public HealthController(KbProperties props) {
        this.role = props.role();
    }

    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "kb-p1-portal", "role", role);
    }
}
