package com.acme.kb.p0.chat;

import com.acme.kb.p0.auth.Actor;
import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.config.P0Properties;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 问答限流按"主体+分钟"计数。Redis 在 P0 只是可重建缓存，限流状态因此放在进程内：
 * 单实例验证足够，多实例由网关承担（记入 P1 backlog）。
 */
@Component
public class ChatRateLimiter {

    private static final int MAX_TRACKED = 4096;

    private final P0Properties props;
    private final Map<String, AtomicInteger> buckets = new ConcurrentHashMap<>();

    public ChatRateLimiter(P0Properties props) {
        this.props = props;
    }

    public void acquire(Actor actor) {
        long bucket = System.currentTimeMillis() / 60_000L;
        int limit = Math.max(1, props.chat().ratePerMinute());
        if (buckets.size() > MAX_TRACKED) {
            evictOld(bucket);
        }
        String key = actor.tenantId() + ":" + actor.userId() + ":" + bucket;
        int seen = buckets.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (seen > limit) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "问答请求已达每分钟上限 " + limit);
        }
    }

    private void evictOld(long bucket) {
        Iterator<Map.Entry<String, AtomicInteger>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            String key = it.next().getKey();
            int at = key.lastIndexOf(':');
            if (at < 0) {
                it.remove();
                continue;
            }
            try {
                if (Long.parseLong(key.substring(at + 1)) < bucket - 1) {
                    it.remove();
                }
            } catch (NumberFormatException e) {
                it.remove();
            }
        }
    }
}
