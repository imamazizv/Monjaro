package com.monjaro.projects;

import com.monjaro.common.PageResponse;
import com.monjaro.projects.ProjectService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    public record CreateProject(@NotBlank @Size(max = 120) String name, @Size(max = 4000) String description) {}
    public record UpdateProject(@Size(min = 1, max = 120) @Pattern(regexp = "(?s).*\\S.*") String name,
                                @Size(max = 4000) String description) {}
    public record AddMember(@NotBlank @Email @Size(max = 254) String email, @NotNull Role role) {}
    public record ChangeRole(@NotNull Role role) {}
    private final ProjectService projects;
    public ProjectController(ProjectService projects) { this.projects = projects; }
    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    @PostMapping
    public ResponseEntity<ProjectView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateProject body) {
        var project = projects.create(actor(jwt), body.name(), body.description());
        return ResponseEntity.created(URI.create("/api/projects/" + project.id())).body(project);
    }

    @GetMapping
    public PageResponse<ProjectView> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return projects.list(actor(jwt), page, size);
    }

    @GetMapping("/{id}")
    public ProjectView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return projects.get(id, actor(jwt)); }

    @PatchMapping("/{id}")
    public ProjectView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateProject body) {
        return projects.update(id, actor(jwt), body.name(), body.description());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { projects.delete(id, actor(jwt)); }

    @GetMapping("/{id}/members")
    public List<MemberView> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return projects.members(id, actor(jwt)); }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody AddMember body) {
        return projects.addMember(id, actor(jwt), body.email(), body.role());
    }

    @PatchMapping("/{id}/members/{userId}")
    public MemberView role(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID userId,
                           @Valid @RequestBody ChangeRole body) {
        return projects.changeRole(id, actor(jwt), userId, body.role());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID userId) {
        projects.removeMember(id, actor(jwt), userId);
    }

    @GetMapping("/{id}/progress")
    public Progress progress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return projects.progress(id, actor(jwt)); }
}
