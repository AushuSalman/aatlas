package com.aatlas.config;

import com.aatlas.common.tenant.TenantContext;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Virtual threads everywhere.
 *
 * <p>Java 21 makes a blocking JDBC call cost almost nothing per thread, which is why the
 * whole codebase stays in plain imperative style and still holds thousands of concurrent
 * requests on four pods.
 *
 * <p>The {@link TaskDecorator} carries the tenant across the hand-off. Without it, work
 * submitted to an executor loses its {@link TenantContext} and either fails loudly or —
 * far worse — runs under whatever tenant the pooled thread last held.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean(name = "applicationTaskExecutor")
    public Executor getAsyncExecutor() {
        TaskExecutorAdapter executor =
                new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
        executor.setTaskDecorator(tenantPropagatingDecorator());
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }

    @Bean
    TaskDecorator tenantPropagatingDecorator() {
        return runnable -> {
            var actor = TenantContext.current().orElse(null);
            var mdc = org.slf4j.MDC.getCopyOfContextMap();
            return () -> {
                var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
                if (mdc != null) {
                    org.slf4j.MDC.setContextMap(mdc);
                }
                try {
                    if (actor != null) {
                        TenantContext.runAs(actor, runnable);
                    } else {
                        runnable.run();
                    }
                } finally {
                    org.slf4j.MDC.clear();
                    if (previousMdc != null) {
                        org.slf4j.MDC.setContextMap(previousMdc);
                    }
                }
            };
        };
    }
}
