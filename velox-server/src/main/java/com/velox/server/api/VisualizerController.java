package com.velox.server.api;

import com.velox.server.livestats.InternalsVisualizer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step mode for the Internals Visualizer (M6.3): {@code POST /api/visualizer/pause} freezes
 * {@link InternalsVisualizer} against real live traffic, {@code /resume} un-freezes it, and
 * {@code /step} applies exactly one access regardless of pause state -- the manual single-step
 * a presenter drives by hand to narrate one operation at a time during a viva.
 */
@RestController
public class VisualizerController {

    private final InternalsVisualizer visualizer;

    public VisualizerController(InternalsVisualizer visualizer) {
        this.visualizer = visualizer;
    }

    @PostMapping("/api/visualizer/pause")
    public ResponseEntity<Void> pause() {
        visualizer.pause();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/visualizer/resume")
    public ResponseEntity<Void> resume() {
        visualizer.resume();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/visualizer/step")
    public ResponseEntity<InternalsVisualizer.Snapshot> step(@RequestParam long key) {
        visualizer.step(key);
        return ResponseEntity.ok(visualizer.describe());
    }
}
