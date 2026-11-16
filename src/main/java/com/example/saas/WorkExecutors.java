package com.example.saas;

import java.util.concurrent.*;

import org.springframework.context.annotation.*;

@Configuration
class WorkExecutors {
    @Bean(name = "outboxExecutor", destroyMethod = "shutdownNow")
    ExecutorService outboxExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().name("outbox-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "jobExecutor", destroyMethod = "shutdownNow")
    ExecutorService jobExecutor() {
        return new ThreadPoolExecutor(
                4,
                4,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().name("saas-job-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
