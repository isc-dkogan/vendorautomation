package com.intersystems.vendorautomation;

import java.sql.SQLException;
import com.intersystems.jdbc.IRISDataSource;

public class IrisDatabaseConnection {

    // public static IRISDataSource createDataSource() throws SQLException
	// {
	// 	IRISDataSource dataSource = new IRISDataSource();
	// 	dataSource.setServerName("localhost");
	// 	dataSource.setPortNumber(41972);
	// 	dataSource.setDatabaseName("B360");
	// 	dataSource.setUser("SuperUser");
	// 	dataSource.setPassword("sys");
	// 	dataSource.setSharedMemory(false);

	// 	return dataSource;
	// }

	public static IRISDataSource createDataSource(String serverName, int portNumber, String databaseName, String username, String password) throws SQLException
	{
		IRISDataSource dataSource = new IRISDataSource();
		dataSource.setServerName(serverName);
		dataSource.setPortNumber(portNumber);
		dataSource.setDatabaseName(databaseName);
		dataSource.setUser(username);
		dataSource.setPassword(password);
		dataSource.setSharedMemory(false);

		return dataSource;
	}
}
