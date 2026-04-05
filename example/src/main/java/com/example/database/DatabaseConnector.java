package com.example.database;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIPrivacy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Database connector with critical infrastructure requiring continuous security auditing.
 * This class handles all database connections and queries for the application.
 */
@AIAudit(checkFor = {"SQL Injection", "Thread Safety issues"})
public class DatabaseConnector {
    
    private final String url;

    @AIPrivacy(reason = "Database credential - never log or include in error messages")
    private final String username;

    @AIPrivacy(reason = "Database credential - never log or include in error messages")
    private final String password;
    private Connection connection;
    
    // Thread-safe connection pool
    private static final Map<String, Connection> connectionPool = new ConcurrentHashMap<>();
    
    public DatabaseConnector(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }
    
    /**
     * Execute a query with parameterized statements to prevent SQL injection.
     */
    public ResultSet executeQuery(String query, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(query);
        
        // Safely set parameters
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        
        return statement.executeQuery();
    }
    
    /**
     * Get a connection from the pool or create a new one.
     * Thread-safe implementation using ConcurrentHashMap.
     */
    public Connection getConnection(String connectionId) throws SQLException {
        return connectionPool.computeIfAbsent(connectionId, id -> {
            try {
                return java.sql.DriverManager.getConnection(url, username, password);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create connection: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Close a connection and return it to the pool.
     */
    public void closeConnection(String connectionId) {
        Connection conn = connectionPool.remove(connectionId);
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                System.err.println("Error closing connection: " + e.getMessage());
            }
        }
    }
    
    /**
     * Execute an update query safely with parameterized statements.
     */
    public int executeUpdate(String query, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(query);
        
        // Safely set parameters
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        
        return statement.executeUpdate();
    }
    
    /**
     * Shutdown the connector and close all pooled connections.
     */
    public void shutdown() {
        connectionPool.forEach((id, conn) -> {
            try {
                conn.close();
            } catch (SQLException e) {
                System.err.println("Error closing connection " + id + ": " + e.getMessage());
            }
        });
        connectionPool.clear();
    }
}
