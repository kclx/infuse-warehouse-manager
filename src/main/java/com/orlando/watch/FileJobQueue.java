package com.orlando.watch;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FileJobQueue {

    private final BlockingQueue<FileProcessJob> queue = new LinkedBlockingQueue<>();

    public void offer(FileProcessJob job) {
        queue.offer(job);
    }

    public FileProcessJob poll() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }
}
