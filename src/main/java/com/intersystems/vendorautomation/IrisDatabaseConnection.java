package com.intersystems.vendorautomation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class IrisDatabaseConnection {

    // Method to connect to the IRIS database
    public static Connection connectToIrisDatabase(String url, String username, String password) {
        Connection connection = null;

        try {
            // Load the IRIS JDBC driver
            Class.forName("com.intersystems.jdbc.IRISDriver");

            // Establish the connection
            connection = DriverManager.getConnection(url, username, password);
            System.out.println("Connected to IRIS database successfully.");

        } catch (ClassNotFoundException e) {
            System.err.println("IRIS JDBC Driver not found: " + e.getMessage());
        } catch (SQLException e) {
            System.err.println("SQL error occurred: " + e.getMessage());
        }

        return connection;
    }
}
