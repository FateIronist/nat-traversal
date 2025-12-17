package com.fate.nat_traversal.service;


import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.TimeUnit;

/**
 * @author fate
 * @date 2025/12/09 20:30
 *
 * 定时任务服务
 */
public interface TaskSchedulerService extends DisposableBean {

    void submit(Runnable task, long delay, long period, TimeUnit timeUnit);

    void shutdown();

    @Override
    default void destroy() throws Exception {
        shutdown();
    }
}
