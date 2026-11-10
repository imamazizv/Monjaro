package com.monjaro.tasks;

import com.monjaro.common.PageResponse;
import com.monjaro.tasks.TaskService.TaskView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/tasks")
public class TaskController {
    private final TaskService tasks;
    public TaskController(TaskService tasks) { this.tasks = tasks; }
    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    @PostMapping
    public ResponseEntity<TaskView> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                          @Valid @RequestBody TaskRequests.Create body) {
        var task = tasks.create(projectId, actor(jwt), body);
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/tasks/" + task.id())).body(task);
    }

    @GetMapping
    public PageResponse<TaskView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
            @RequestParam(required = false) TaskStatus status, @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return tasks.list(projectId, actor(jwt), status, priority, assigneeId, page, size);
    }

    @GetMapping("/{taskId}")
    public TaskView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId, @PathVariable UUID taskId) {
        return tasks.get(projectId, taskId, actor(jwt));
    }

    @PatchMapping("/{taskId}")
    public TaskView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId, @PathVariable UUID taskId,
                           @Valid @RequestBody TaskRequests.Patch body) {
        return tasks.update(projectId, taskId, actor(jwt), body);
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId, @PathVariable UUID taskId) {
        tasks.delete(projectId, taskId, actor(jwt));
    }
}
