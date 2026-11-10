package com.monjaro.tasks;

import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public final class TaskRequests {
    private TaskRequests() {}

    public record Create(@NotBlank @Size(max = 200) String title, @Size(max = 8000) String description,
                         TaskStatus status, Priority priority, UUID assigneeId, LocalDate dueDate) {}

    public static class Patch {
        @Size(min = 1, max = 200) @Pattern(regexp = "(?s).*\\S.*")
        public String title;
        @Size(max = 8000)
        public String description;
        public TaskStatus status;
        public Priority priority;
        private UUID assigneeId;
        private LocalDate dueDate;
        private boolean assigneePresent;
        private boolean dueDatePresent;

        @JsonSetter("assigneeId")
        public void setAssigneeId(UUID value) { assigneePresent = true; assigneeId = value; }
        @Schema(nullable = true, description = "Omit to preserve; send null to unassign")
        public UUID getAssigneeId() { return assigneeId; }
        @JsonSetter("dueDate")
        public void setDueDate(LocalDate value) { dueDatePresent = true; dueDate = value; }
        @Schema(nullable = true, description = "Omit to preserve; send null to clear")
        public LocalDate getDueDate() { return dueDate; }
        public boolean assigneePresent() { return assigneePresent; }
        public boolean dueDatePresent() { return dueDatePresent; }
    }
}
