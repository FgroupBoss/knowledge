package com.acme.kb.p0.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SSE/流式任务专用执行器：JDK 21 虚拟线程，阻塞在网络 IO 上不占用平台线程。 */
@Configuration
public class AsyncInfra {

    @Bean(destroyMethod = "close")
    public ExecutorService sseExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
