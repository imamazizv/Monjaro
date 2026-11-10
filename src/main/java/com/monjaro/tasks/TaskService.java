package com.monjaro.tasks;

import com.monjaro.common.*;
import com.monjaro.projects.ProjectService;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TaskService {
    public record TaskView(UUID id, UUID projectId, String title, String description, TaskStatus status,
                           Priority priority, UUID assigneeId, LocalDate dueDate, Instant createdAt, Instant updatedAt) {}
    private final JdbcClient jdbc;
    private final ProjectService projects;
    public TaskService(JdbcClient jdbc, ProjectService projects) { this.jdbc = jdbc; this.projects = projects; }

    public TaskView create(UUID project, UUID actor, TaskRequests.Create body) {
        projects.requireEditor(project, actor);
        validateAssignee(project, body.assigneeId());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tasks (id, project_id, title, description, status, priority, assignee_id, due_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, project, body.title().strip(), body.description() == null ? "" : body.description(),
                body.status() == null ? "TODO" : body.status().name(),
                body.priority() == null ? "MEDIUM" : body.priority().name(), body.assigneeId(), body.dueDate()).update();
        return get(project, id, actor);
    }

    @Transactional(readOnly = true)
    public TaskView get(UUID project, UUID id, UUID actor) {
        projects.requireMember(project, actor);
        return find(project, id);
    }

    private TaskView find(UUID project, UUID id) {
        return jdbc.sql("SELECT * FROM tasks WHERE project_id = ? AND id = ?").params(project, id)
                .query(TaskService::map).optional().orElseThrow(() -> ApiException.notFound("Task"));
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskView> list(UUID project, UUID actor, TaskStatus status, Priority priority,
                                       UUID assignee, int page, int size) {
        projects.requireMember(project, actor);
        String where = " WHERE project_id = :project";
        Map<String, Object> params = new HashMap<>();
        params.put("project", project);
        if (status != null) { where += " AND status = :status"; params.put("status", status.name()); }
        if (priority != null) { where += " AND priority = :priority"; params.put("priority", priority.name()); }
        if (assignee != null) { where += " AND assignee_id = :assignee"; params.put("assignee", assignee); }
        long total = jdbc.sql("SELECT count(*) FROM tasks" + where).params(params).query(Long.class).single();
        params.put("size", size); params.put("offset", (long) page * size);
        var tasks = jdbc.sql("SELECT * FROM tasks" + where + " ORDER BY created_at DESC, id LIMIT :size OFFSET :offset")
                .params(params).query(TaskService::map).list();
        return new PageResponse<>(tasks, page, size, total);
    }

    public TaskView update(UUID project, UUID id, UUID actor, TaskRequests.Patch body) {
        projects.requireEditor(project, actor);
        TaskView old = find(project, id);
        UUID assignee = body.assigneePresent() ? body.getAssigneeId() : old.assigneeId();
        validateAssignee(project, assignee);
        jdbc.sql("""
                UPDATE tasks SET title = ?, description = ?, status = ?, priority = ?, assignee_id = ?,
                due_date = ?, updated_at = now() WHERE project_id = ? AND id = ?
                """).params(body.title == null ? old.title() : body.title.strip(),
                body.description == null ? old.description() : body.description,
                body.status == null ? old.status().name() : body.status.name(),
                body.priority == null ? old.priority().name() : body.priority.name(), assignee,
                body.dueDatePresent() ? body.getDueDate() : old.dueDate(), project, id).update();
        return find(project, id);
    }

    public void delete(UUID project, UUID id, UUID actor) {
        projects.requireEditor(project, actor);
        if (jdbc.sql("DELETE FROM tasks WHERE project_id = ? AND id = ?").params(project, id).update() == 0)
            throw ApiException.notFound("Task");
    }

    private void validateAssignee(UUID project, UUID assignee) {
        if (assignee != null && jdbc.sql("SELECT count(*) FROM project_memberships WHERE project_id = ? AND user_id = ?")
                .params(project, assignee).query(Long.class).single() == 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Assignee must be a member of this project");
    }

    private static TaskView map(ResultSet row, int index) throws SQLException {
        return new TaskView(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
                row.getString("title"), row.getString("description"), TaskStatus.valueOf(row.getString("status")),
                Priority.valueOf(row.getString("priority")), row.getObject("assignee_id", UUID.class),
                row.getObject("due_date", LocalDate.class), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }
}
