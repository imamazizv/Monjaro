package com.monjaro.users;

import com.monjaro.common.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserStore {
    private final JdbcClient jdbc;
    public UserStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    public UserView get(UUID id) {
        return jdbc.sql("SELECT id, username, email FROM users WHERE id = ?").param(id)
                .query(UserStore::map).optional().orElseThrow(() -> ApiException.notFound("User"));
    }

    public UserView byEmail(String email) {
        return jdbc.sql("SELECT id, username, email FROM users WHERE email = ?")
                .param(email.strip().toLowerCase(Locale.ROOT)).query(UserStore::map).optional()
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    private static UserView map(ResultSet row, int index) throws SQLException {
        return new UserView(row.getObject("id", UUID.class), row.getString("username"), row.getString("email"));
    }
}
