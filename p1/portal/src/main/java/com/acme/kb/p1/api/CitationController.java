package com.acme.kb.p1.api;

import com.acme.kb.p1.auth.ActorHolder;
import com.acme.kb.p1.chat.CitationService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 引用预览：正文经服务端代理返回，禁用任何缓存，不提供对象存储直链。 */
@RestController
@RequestMapping("/api/v1")
public class CitationController {

    private final CitationService citations;

    public CitationController(CitationService citations) {
        this.citations = citations;
    }

    @GetMapping("/citations/{citationId}/preview")
    public ResponseEntity<String> preview(@PathVariable String citationId) {
        String body = citations.preview(ActorHolder.require(), citationId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
