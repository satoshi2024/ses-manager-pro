package com.ses.service.portal.impl;

import com.ses.service.portal.PortalRateLimiter;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * インメモリスライディングウィンドウrate limiter。キーごとに1分窓のリクエスト時刻列を保持する。
 * 超過分は窓から追い出し、窓内件数が上限に達したら拒否する。
 */
@Service
public class PortalRateLimiterImpl implements PortalRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int perMinute) {
        if (perMinute <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        ArrayDeque<Long> deque = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() >= WINDOW_MILLIS) {
                deque.pollFirst();
            }
            if (deque.size() >= perMinute) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }
}
