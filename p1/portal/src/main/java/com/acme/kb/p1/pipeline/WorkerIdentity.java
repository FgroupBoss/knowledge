package com.acme.kb.p1.pipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 单个 worker 进程的稳定标识：租约、心跳、删除/入库消费共用同一个 owner。 */
@Component
public class WorkerIdentity {

    private final String id;

    public WorkerIdentity(@Value("${KB_WORKER_ID:}") String configured) {
        this.id = configured == null || configured.isBlank()
                ? "worker-" + UUID.randomUUID().toString().substring(0, 8)
                : configured;
    }

    public String id() {
        return id;
    }
}
