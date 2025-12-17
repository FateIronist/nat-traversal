package com.fate.nat_traversal.service.impl;

import com.fate.nat_traversal.service.TaskSchedulerService;
import org.springframework.stereotype.Service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author fate
 * @date 2025/12/09 20:30
 *
 * 定时任务服务
 */
@Service
public class TaskSchedulerServiceImpl implements TaskSchedulerService {
    private static final ScheduledExecutorService taskScheduler = new ScheduledThreadPoolExecutor(1);

    @Override
    public void submit(Runnable task, long delay, long period, TimeUnit timeUnit) {
        taskScheduler.scheduleAtFixedRate(task, delay, period, timeUnit);
    }

    @Override
    public void shutdown() {
        taskScheduler.shutdown();
    }
}
