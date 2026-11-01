package com.acme.kb.p0.authz;

import com.acme.kb.p0.config.P0Properties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * 授权缓存只作优化层，不是事实源：
 * - 键带 kbId+userId，值里带 aclVersion；
 * - 权限变更时递增 DB 版本、抬升 Redis 水位并精确删除受影响键（不使用 KEYS/SCAN）；
 * - 读取时比较水位：水位缺失、值过旧、Redis 异常 → 一律回源 MySQL；
 * - 因此 Redis 被清空或不可用只会损失性能，不会放宽权限（对应验收项 6.1）。
 */
@Service
public class AclCacheService {

    private static final Logger log = LoggerFactory.getLogger(AclCacheService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final P0Properties props;

    public final LongAdder hits = new LongAdder();
    public final LongAdder misses = new LongAdder();
    public final LongAdder staleRejected = new LongAdder();
    public final LongAdder redisErrors = new LongAdder();
    public final LongAdder invalidations = new LongAdder();

    public AclCacheService(StringRedisTemplate redis, ObjectMapper mapper, P0Properties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
    }

    private String decisionKey(String kbId, String userId) {
        return props.cache().namespace() + ":acl:" + kbId + ":" + userId;
    }

    private String watermarkKey(String kbId) {
        return props.cache().namespace() + ":aclwm:" + kbId;
    }

    public Optional<AclDecision> find(String kbId, String userId) {
        if (!props.cache().enabled()) {
            return Optional.empty();
        }
        try {
            Long watermark = parseLong(redis.opsForValue().get(watermarkKey(kbId)));
            if (watermark == null) {
                misses.increment();
                return Optional.empty();
            }
            String raw = redis.opsForValue().get(decisionKey(kbId, userId));
            if (raw == null) {
                misses.increment();
                return Optional.empty();
            }
            AclDecision cached = mapper.readValue(raw, new TypeReference<AclDecision>() {
            });
            if (cached.aclVersion() < watermark) {
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
        if (!props.cache().enabled()) {
            return;
        }
        try {
            redis.opsForValue().set(decisionKey(decision.kbId(), decision.userId()),
                    mapper.writeValueAsString(decision),
                    Duration.ofSeconds(props.cache().ttlSeconds()));
        } catch (Exception e) {
            redisErrors.increment();
            log.warn("acl cache write failed (ignored, cache is not source of truth): {}",
                    e.getClass().getSimpleName());
        }
    }

    /** 权限变更：抬升水位并精确删除该 KB 下受影响主体的缓存决策。 */
    public void invalidate(String kbId, long newAclVersion, Collection<String> userIds) {
        try {
            redis.opsForValue().set(watermarkKey(kbId), String.valueOf(newAclVersion),
                    Duration.ofSeconds(Math.max(props.cache().ttlSeconds() * 10, 600)));
            if (!userIds.isEmpty()) {
                List<String> keys = userIds.stream().map(u -> decisionKey(kbId, u)).toList();
                redis.delete(keys);
            }
            invalidations.increment();
        } catch (Exception e) {
            redisErrors.increment();
            log.warn("acl invalidation failed for kb={} v={}: {}", kbId, newAclVersion,
                    e.getClass().getSimpleName());
        }
    }

    /** 验证夹具：写入一个过期版本的决策，用于证明“缓存旧权限不得放行”。 */
    public void poisonWithStaleDecision(String kbId, String userId, long staleAclVersion, String role) {
        try {
            AclDecision stale = new AclDecision(kbId, userId, "poison-tenant", "poison-workspace",
                    role, java.util.Set.of("kb:read", "kb:write"), "INTERNAL", staleAclVersion, true,
                    System.currentTimeMillis());
            redis.opsForValue().set(decisionKey(kbId, userId), mapper.writeValueAsString(stale),
                    Duration.ofSeconds(120));
        } catch (Exception e) {
            throw new IllegalStateException("poison write failed", e);
        }
    }

    private static Long parseLong(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public CacheStats stats() {
        return new CacheStats(hits.sum(), misses.sum(), staleRejected.sum(), redisErrors.sum(),
                invalidations.sum());
    }

    public record CacheStats(long hits, long misses, long staleRejected, long redisErrors, long invalidations) {
    }
}
