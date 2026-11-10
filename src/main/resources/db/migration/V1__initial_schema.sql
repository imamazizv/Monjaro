CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(40) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT email_normalized CHECK (email = lower(email)),
    CONSTRAINT username_normalized CHECK (username = lower(username))
);

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(4000) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project_memberships (
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(10) NOT NULL CHECK (role IN ('OWNER', 'MEMBER', 'VIEWER')),
    PRIMARY KEY (project_id, user_id)
);
CREATE UNIQUE INDEX one_owner_per_project ON project_memberships(project_id) WHERE role = 'OWNER';
CREATE INDEX memberships_by_user ON project_memberships(user_id);

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(8000) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    assignee_id UUID,
    due_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT assignee_is_project_member FOREIGN KEY (project_id, assignee_id)
      REFERENCES project_memberships(project_id, user_id)
);
CREATE INDEX tasks_by_project ON tasks(project_id, created_at DESC, id);
CREATE INDEX tasks_by_assignee ON tasks(project_id, assignee_id);
