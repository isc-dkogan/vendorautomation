package com.intersystems.vendorautomation;

import java.sql.SQLException;
import com.intersystems.jdbc.IRISDataSource;

public class IrisDatabaseConnection {

    public IRISDataSource createDataSource() throws SQLException
	{
		IRISDataSource dataSource = new IRISDataSource();
		dataSource.setServerName("localhost");
		dataSource.setPortNumber(41972);
		dataSource.setDatabaseName("B360");
		dataSource.setUser("SuperUser");
		dataSource.setPassword("sys");
		dataSource.setSharedMemory(false);
		return dataSource;
	}
}
