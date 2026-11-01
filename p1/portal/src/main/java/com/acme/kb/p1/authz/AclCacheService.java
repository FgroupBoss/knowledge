package com.acme.kb.p1.authz;

import com.acme.kb.p1.config.KbProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * 授权缓存只作优化层，不是事实源。P1 的读法（对应 backlog #5）：
 * - 每次授权先读一次 MySQL 的 {@code knowledge_base.acl_version}，把版本作为缓存键的一部分：
 *   {@code p1:acl:{kbId}:{userId}:v{aclVersion}}；
 * - 键里带版本意味着撤权后即使一个 Redis 键都没删，旧决策也不可能被读到；
 * - 值和 DB 版本不一致同样视为未命中（防污染/防手工写入的旧值）；
 * - Redis 异常、值为空一律回源 MySQL，权限正确性不依赖缓存的可用性。
 */
@Service
public class AclCacheService {

    private static final Logger log = LoggerFactory.getLogger(AclCacheService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final KbProperties props;

    public final LongAdder hits = new LongAdder();
    public final LongAdder misses = new LongAdder();
    public final LongAdder staleRejected = new LongAdder();
    public final LongAdder redisErrors = new LongAdder();
    public final LongAdder invalidations = new LongAdder();

    public AclCacheService(StringRedisTemplate redis, ObjectMapper mapper, KbProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
    }

    private String decisionKey(String kbId, String userId, long aclVersion) {
        return props.cache().namespace() + ":acl:" + kbId + ":" + userId + ":v" + aclVersion;
    }

    private String pointerKey(String kbId, String userId) {
        return props.cache().namespace() + ":aclp:" + kbId + ":" + userId;
    }

    /**
     * 只在缓存版本与 DB 当前版本完全一致时命中；调用方必须传入刚从 MySQL 读到的版本。
     */
    public Optional<AclDecision> find(String kbId, String userId, long currentAclVersion) {
        if (!props.cache().enabled()) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(decisionKey(kbId, userId, currentAclVersion));
            if (raw == null) {
                misses.increment();
                return Optional.empty();
            }
            AclDecision cached = mapper.readValue(raw, new TypeReference<AclDecision>() {
            });
            if (cached.aclVersion() != currentAclVersion
                    || !kbId.equals(cached.kbId())
                    || !userId.equals(cached.userId())) {
                // 键与值版本不一致：按未命中处理，绝不放行
                staleRejected.increment();
                misses.increment();
                return Optional.empty();
            }
            hits.increment();
            return Optional.of(cached);
        } catch (Exception e) {
            redisErrors.increment();
            log.warn("acl cache read failed, fall back to MySQL: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void store(AclDecision decision) {
        if (!props.cache().enabled() || !decision.exists()) {
            return;
        }
        Duration ttl = Duration.ofSeconds(props.cache().ttlSeconds());
        try {
            String pointer = pointerKey(decision.kbId(), decision.userId());
            redis.opsForValue().set(decisionKey(decision.kbId(), decision.userId(), decision.aclVersion()),
                    mapper.writeValueAsString(decision), ttl);
            redis.opsForValue().set(pointer, String.valueOf(decision.aclVersion()), ttl);
        } catch (Exception e) {
            redisErrors.increment();
            log.warn("acl cache write failed (ignored, cache is not source of truth): {}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 权限变更后的清理：只删“指针指向的那个旧版本键”。删除失败或漏删都不影响正确性——
     * 新请求会用新版本拼键，旧键永远不会被再次读取。这里清理的是内存，不是权限。
     */
    public void invalidate(String kbId, long newAclVersion, Collection<String> userIds) {
        try {
            for (String userId : userIds) {
                String pointer = pointerKey(kbId, userId);
                String previous = redis.opsForValue().get(pointer);
                if (previous != null) {
                    redis.delete(decisionKey(kbId, userId, parseLong(previous, 0)));
                }
                redis.delete(pointer);
            }
            invalidations.increment();
        } catch (Exception e) {
            redisErrors.increment();
            log.warn("acl invalidation failed for kb={} v={}: {}", kbId, newAclVersion,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 验证夹具：把一个旧版本决策写到旧版本键并设置指针，用于证明
     * “即使旧缓存还在、甚至没删，新版本授权也不会命中它”。
     */
    public void poisonWithStaleDecision(String kbId, String userId, long staleAclVersion, String role) {
        try {
            AclDecision stale = new AclDecision(kbId, userId, "poison-tenant", "poison-workspace",
                    role, java.util.Set.of("kb:read", "kb:write"), "INTERNAL", staleAclVersion, true,
                    System.currentTimeMillis());
            redis.opsForValue().set(decisionKey(kbId, userId, staleAclVersion),
                    mapper.writeValueAsString(stale), Duration.ofSeconds(120));
            redis.opsForValue().set(pointerKey(kbId, userId), String.valueOf(staleAclVersion),
                    Duration.ofSeconds(120));
        } catch (Exception e) {
            throw new IllegalStateException("poison write failed", e);
        }
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public CacheStats stats() {
        return new CacheStats(hits.sum(), misses.sum(), staleRejected.sum(), redisErrors.sum(),
                invalidations.sum());
    }

    public record CacheStats(long hits, long misses, long staleRejected, long redisErrors, long invalidations) {
    }
}
