package com.intersystems.vendorautomation;

import java.sql.Connection;
import java.sql.SQLException;

public class App {
    public static void main(String[] args) throws Exception {
        System.out.println("Hello, World!");

        String url = "jdbc:IRIS://localhost:41972/B360";
        String username = "SuperUser";
        String password = "sys";

        Connection conn = IrisDatabaseConnection.connectToIrisDatabase(url, username, password);

        if (conn != null) {
            try {
                // Close the connection after use
                conn.close();
                System.out.println("Connection closed.");
            } catch (SQLException e) {
                System.err.println("Error closing the connection: " + e.getMessage());
            }
        }
    }
}
