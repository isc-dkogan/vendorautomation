package com.intersystems.vendorautomation;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.CallableStatement;

public class App {
    public static void main(String[] args) throws Exception {
        System.out.println("Hello, World!");

        String url = "jdbc:IRIS://localhost:41972/B360";
        String username = "SuperUser";
        String password = "sys";

        Connection conn = IrisDatabaseConnection.connectToIrisDatabase(url, username, password);

        App app = new App();

        app.TestStoredProcedure(conn);

        if (conn != null) {
            try {
                conn.close();
                System.out.println("Connection closed.");
            } catch (SQLException e) {
                System.err.println("Error closing the connection: " + e.getMessage());
            }
        }
    }

    public void TestStoredProcedure(Connection conn) {
        CallableStatement stmt = null;
        String outputValue = null;

        try {
            String sql = "{CALL SDS_DataLoader.TestStoredProc(?)}";
            stmt = conn.prepareCall(sql);

            stmt.registerOutParameter(1, java.sql.Types.VARCHAR);

            stmt.execute();

            outputValue = stmt.getString(1);

            System.out.println("Stored procedure returned: " + outputValue);
        }

        catch (SQLException e) {
            e.printStackTrace();
        }

        finally {
            try {
                if (stmt != null) stmt.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

    }
}
