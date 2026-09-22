package com.velox.server.livestats;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/stats/stream}: the SSE feed the live dashboard's {@code EventSource} connects
 * to. M6.1 -- see {@link LiveStatsBroadcaster} for the actual 10 Hz tick and
 * {@code docs/SYSTEM.md} for why SSE over a WebSocket (one-way, no handshake complexity, and
 * {@code EventSource} reconnects on its own).
 */
@RestController
public class LiveStatsController {

    /** No timeout: the connection is expected to live as long as the browser tab does; the
     * client's own {@code EventSource} reconnects automatically if it ever drops. */
    private static final long NO_TIMEOUT = 0L;

    private final LiveStatsBroadcaster broadcaster;

    public LiveStatsController(LiveStatsBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping("/api/stats/stream")
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        broadcaster.subscribe(emitter);
        return emitter;
    }
}
