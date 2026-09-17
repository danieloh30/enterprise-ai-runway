package com.danieloh.demo.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;

/** Only call with application-owned SQL; every external value is bound. */
@ApplicationScoped
public class Database {
    @Inject AgroalDataSource source;
    @Inject ObjectMapper mapper;

    public List<JsonNode> query(String sql, Object... values) {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<JsonNode>();
                while (rows.next()) result.add(mapper.readTree(rows.getString(1)));
                return result;
            }
        } catch (Exception e) { throw new IllegalStateException("Database operation failed", e); }
    }

    public int update(String sql, Object... values) {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Database operation failed", e); }
    }

    private void bind(PreparedStatement statement, Object[] values) throws SQLException {
        statement.setQueryTimeout(10);
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid JSON value", e); }
    }
}
