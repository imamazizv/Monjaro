package com.monjaro.projects;

import com.monjaro.common.*;
import com.monjaro.users.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectService {
    public record ProjectView(UUID id, String name, String description, Instant createdAt, Instant updatedAt, Role role) {}
    public record MemberView(UUID userId, String username, String email, Role role) {}
    public record Progress(long total, long todo, long inProgress, long done, double completionPercentage) {}
    private final JdbcClient jdbc;
    private final UserStore users;

    public ProjectService(JdbcClient jdbc, UserStore users) { this.jdbc = jdbc; this.users = users; }

    /** Serialize project writes with membership changes so permission checks cannot race removal. */
    public void lock(UUID projectId) {
        jdbc.sql("SELECT id FROM projects WHERE id = ? FOR UPDATE").param(projectId).query(UUID.class)
                .optional().orElseThrow(() -> ApiException.notFound("Project"));
    }

    public Role requireMember(UUID projectId, UUID userId) {
        return jdbc.sql("SELECT role FROM project_memberships WHERE project_id = ? AND user_id = ?")
                .params(projectId, userId).query(String.class).optional().map(Role::valueOf)
                .orElseThrow(() -> ApiException.notFound("Project"));
    }

    public void requireEditor(UUID projectId, UUID userId) {
        lock(projectId);
        if (requireMember(projectId, userId) == Role.VIEWER)
            throw new ApiException(HttpStatus.FORBIDDEN, "Viewers cannot modify tasks");
    }

    private void requireOwner(UUID projectId, UUID userId) {
        lock(projectId);
        if (requireMember(projectId, userId) != Role.OWNER)
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the project owner can perform this operation");
    }

    public ProjectView create(UUID actor, String name, String description) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO projects (id, name, description) VALUES (?, ?, ?)")
                .params(id, name.strip(), description == null ? "" : description).update();
        jdbc.sql("INSERT INTO project_memberships (project_id, user_id, role) VALUES (?, ?, 'OWNER')")
                .params(id, actor).update();
        return get(id, actor);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectView> list(UUID actor, int page, int size) {
        var content = jdbc.sql("""
                SELECT p.*, m.role FROM projects p JOIN project_memberships m ON m.project_id = p.id
                WHERE m.user_id = ? ORDER BY p.created_at DESC, p.id LIMIT ? OFFSET ?
                """).params(actor, size, (long) page * size).query(ProjectService::map).list();
        long count = jdbc.sql("SELECT count(*) FROM project_memberships WHERE user_id = ?")
                .param(actor).query(Long.class).single();
        return new PageResponse<>(content, page, size, count);
    }

    @Transactional(readOnly = true)
    public ProjectView get(UUID id, UUID actor) {
        return jdbc.sql("""
                SELECT p.*, m.role FROM projects p JOIN project_memberships m ON m.project_id = p.id
                WHERE p.id = ? AND m.user_id = ?
                """).params(id, actor).query(ProjectService::map).optional()
                .orElseThrow(() -> ApiException.notFound("Project"));
    }

    public ProjectView update(UUID id, UUID actor, String name, String description) {
        requireOwner(id, actor);
        jdbc.sql("UPDATE projects SET name = coalesce(?, name), description = coalesce(?, description), updated_at = now() WHERE id = ?")
                .params(name == null ? null : name.strip(), description, id).update();
        return get(id, actor);
    }

    public void delete(UUID id, UUID actor) {
        requireOwner(id, actor);
        jdbc.sql("DELETE FROM projects WHERE id = ?").param(id).update();
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(UUID id, UUID actor) {
        requireMember(id, actor);
        return jdbc.sql("""
                SELECT u.id, u.username, u.email, m.role FROM project_memberships m
                JOIN users u ON u.id = m.user_id WHERE m.project_id = ? ORDER BY u.username
                """).param(id).query((row, index) -> new MemberView(row.getObject("id", UUID.class),
                        row.getString("username"), row.getString("email"), Role.valueOf(row.getString("role")))).list();
    }

    public MemberView addMember(UUID id, UUID actor, String email, Role role) {
        requireOwner(id, actor);
        validateAssignableRole(role);
        UserView user = users.byEmail(email);
        jdbc.sql("INSERT INTO project_memberships (project_id, user_id, role) VALUES (?, ?, ?)")
                .params(id, user.id(), role.name()).update();
        return new MemberView(user.id(), user.username(), user.email(), role);
    }

    public MemberView changeRole(UUID id, UUID actor, UUID target, Role role) {
        requireOwner(id, actor);
        validateAssignableRole(role);
        protectOwner(id, target);
        jdbc.sql("UPDATE project_memberships SET role = ? WHERE project_id = ? AND user_id = ?")
                .params(role.name(), id, target).update();
        UserView user = users.get(target);
        return new MemberView(target, user.username(), user.email(), role);
    }

    public void removeMember(UUID id, UUID actor, UUID target) {
        requireOwner(id, actor);
        protectOwner(id, target);
        // Unassign first; the composite FK prevents orphaned assignees.
        jdbc.sql("UPDATE tasks SET assignee_id = NULL, updated_at = now() WHERE project_id = ? AND assignee_id = ?")
                .params(id, target).update();
        jdbc.sql("DELETE FROM project_memberships WHERE project_id = ? AND user_id = ?").params(id, target).update();
    }

    private void protectOwner(UUID id, UUID target) {
        Role role = jdbc.sql("SELECT role FROM project_memberships WHERE project_id = ? AND user_id = ?")
                .params(id, target).query(String.class).optional().map(Role::valueOf)
                .orElseThrow(() -> ApiException.notFound("Membership"));
        if (role == Role.OWNER) throw new ApiException(HttpStatus.CONFLICT, "The project owner cannot be removed or demoted");
    }

    private void validateAssignableRole(Role role) {
        if (role == Role.OWNER) throw new ApiException(HttpStatus.BAD_REQUEST, "Only MEMBER or VIEWER can be assigned");
    }

    @Transactional(readOnly = true)
    public Progress progress(UUID id, UUID actor) {
        requireMember(id, actor);
        return jdbc.sql("""
                SELECT count(*) AS total,
                count(*) FILTER (WHERE status = 'TODO') AS todo,
                count(*) FILTER (WHERE status = 'IN_PROGRESS') AS in_progress,
                count(*) FILTER (WHERE status = 'DONE') AS done
                FROM tasks WHERE project_id = ?
                """).param(id).query((row, index) -> {
                    long total = row.getLong("total"), done = row.getLong("done");
                    return new Progress(total, row.getLong("todo"), row.getLong("in_progress"), done,
                            total == 0 ? 0 : Math.round(done * 10000.0 / total) / 100.0);
                }).single();
    }

    private static ProjectView map(ResultSet row, int index) throws SQLException {
        return new ProjectView(row.getObject("id", UUID.class), row.getString("name"), row.getString("description"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(), Role.valueOf(row.getString("role")));
    }
}
