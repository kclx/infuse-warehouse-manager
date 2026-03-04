package com.orlando.watch.queue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.orlando.watch.model.FileWatchTask;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * 监听器与定时处理器之间共享的内存任务队列。
 */
@ApplicationScoped
public class FileWatchTaskQueue {

    private final BlockingQueue<FileWatchTask> queue = new LinkedBlockingQueue<>();

    public void offer(FileWatchTask task) {
        queue.offer(task);
    }

    public FileWatchTask poll() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }
}
