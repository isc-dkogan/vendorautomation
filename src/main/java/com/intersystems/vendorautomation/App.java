package com.intersystems.vendorautomation;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;

import com.intersystems.jdbc.IRIS;
import com.intersystems.jdbc.IRISConnection;
import com.intersystems.jdbc.IRISDataSource;
import com.intersystems.jdbc.IRISObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Set;
import java.sql.Statement;

import org.json.JSONArray;
import org.json.JSONObject;

public class App {
    public static void main(String[] args) throws Exception {
        System.out.println("Hello, World!");

        IrisDatabaseConnection conn = new IrisDatabaseConnection();
        IRISDataSource dataSource = conn.createDataSource();
        IRISConnection connection = (IRISConnection) dataSource.getConnection();
        IRIS iris = IRIS.createIRIS(connection);

        App app = new App();
        int newDataSourceId = app.DuplicateDataSource(iris, 2, "ISCSalesforcePackage");
        JSONArray dataSourceItems = app.GetDataSourceItems(newDataSourceId);
        app.ImportDataSchemaDefinitions(iris, newDataSourceId, dataSourceItems);
        // app.PublishDataSchemaDefinitions(iris, connection, newDataSourceId);
    }

    public int DuplicateDataSource(IRIS iris, int dataSourceId, String newDataSourceName) {
        System.out.println("DuplicateDataSource() dataSourceId=" + dataSourceId);

        int newDataSourceId = 0;

        try {
            IRISObject originalDataSource = (IRISObject) iris.classMethodObject("SDS.DataLoader.DS.DataSource", "%OpenId", dataSourceId);

            IRISObject newDataSource = (IRISObject) originalDataSource.invoke("%ConstructClone", 0);

            newDataSource.set("Name", newDataSourceName);

            Object sc = newDataSource.invoke("%Save");

            String id = (String) newDataSource.invoke("%Id");

            newDataSourceId = Integer.parseInt(id);
        }

        catch (Exception e) {
            e.printStackTrace();
        }

        return newDataSourceId;

    }

    public void PublishDataSchemaDefinitions(IRIS iris, IRISConnection conn, int dataSourceId) {
        System.out.println("PublishDataSchemaDefinitions() dataSourceId=" + dataSourceId);

        try {
            String query = "SELECT ID FROM SDS_DataCatalog.DataSchemaDefinition WHERE DataSource = ?";

            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setInt(1, dataSourceId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String id = rs.getString("ID");
                System.out.println("DataSchemaDefinition id = " + id);

                iris.classMethodObject("SDS.API.DataCatalogAPI", "SchemaDefinitionSessionClose", id, 1);
            }

            stmt.close();
        }

        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void ImportDataSchemaDefinitions(IRIS iris, int dataSourceId, JSONArray itemsArray) {
        System.out.println("ImportDataSchemaDefinitions() dataSourceId=" + dataSourceId);

        try {
            IRISObject dataCatalogService = (IRISObject) iris.classMethodObject("SDS.DataCatalog.BS.Service", "%New", "Data Catalog Service");

            for (int i = 0; i < itemsArray.length(); i++) {
                JSONObject item = itemsArray.getJSONObject(i);
                System.out.println("Item " + (i + 1) + ": " + item.toString());

                IRISObject importRequest = (IRISObject) iris.classMethodObject("SDS.DataCatalog.BO.ImportRequest", "%New");
                importRequest.set("BatchId", 1);
                importRequest.set("DataSourceId", dataSourceId);
                importRequest.set("MemberName", item.getString("memberName"));
                // importRequest.set("SchemaName", item.getString("schemaName"));

                dataCatalogService.invoke("ProcessInput", importRequest);
            }

        }

        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public JSONArray GetDataSourceItems(int dataSourceId) {
        System.out.println("GetDataSourceItems() dataSourceId=" + dataSourceId);

        JSONArray itemsArray = null;

        try {
            URL url = new URL("http://localhost:8081/intersystems/data-loader/v1/dataSources/"+dataSourceId+"/schemas/members");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            String userCredentials = "systemadmin:sys";
            String basicAuth = "Basic " + new String(Base64.getEncoder().encode(userCredentials.getBytes()));
            connection.setRequestProperty("Authorization", basicAuth);

            int responseCode = connection.getResponseCode();
            System.out.println("Response Code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuilder content = new StringBuilder();

                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }

                in.close();

                String jsonResponse = content.toString();
                JSONObject jsonObject = new JSONObject(jsonResponse);

                if (jsonObject.has("items")) {
                    itemsArray = jsonObject.getJSONArray("items");
                } else {
                    System.out.println("No 'items' key found in the response.");
                }
            } else {
                System.out.println("Request failed with response code: " + responseCode);
            }

            connection.disconnect();

        }

        catch (Exception e) {
            e.printStackTrace();
        }

        return itemsArray;

    }
}
