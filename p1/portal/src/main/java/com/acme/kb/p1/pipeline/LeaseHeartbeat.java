package com.acme.kb.p1.pipeline;

import com.acme.kb.p1.config.KbProperties;
import com.acme.kb.p1.repo.PipelineCommandMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 租约心跳：单文档解析/索引可能超过 45s，worker 每 15s 续租一次，
 * 避免第二个 worker 在租约到期时把同一任务抢走；进程崩溃后心跳停止，租约自然到期可被接管。
 *
 * <p>只在 worker 角色启用：只有持有租约的进程才需要续租。</p>
 */
@Component
@ConditionalOnProperty(prefix = "kb", name = "role", havingValue = "worker")
public class LeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);

    private final PipelineCommandMapper pipeline;
    private final WorkerIdentity worker;
    private final KbProperties props;

    public LeaseHeartbeat(PipelineCommandMapper pipeline, WorkerIdentity worker, KbProperties props) {
        this.pipeline = pipeline;
        this.worker = worker;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "#{${kb.ingest.heartbeat-seconds:15} * 1000}")
    @Transactional
    public void renew() {
        Instant now = Instant.now();
        Instant until = now.plusSeconds(props.ingest().leaseSeconds());
        try {
            int renewed = pipeline.heartbeat(worker.id(), until, now);
            if (renewed > 0) {
                log.debug("lease_heartbeat worker={} renewed={} until={}", worker.id(), renewed, until);
            }
        } catch (RuntimeException e) {
            // 心跳失败不主动放弃任务：让租约自然到期，由下一个消费者接管
            log.warn("lease_heartbeat_failed worker={} err={}", worker.id(), e.getClass().getSimpleName());
        }
    }
}
