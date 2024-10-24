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
import java.util.Base64;
import java.util.Set;

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
        // String newDataSourceId = app.DuplicateSalesforceDataSource(iris, 2, "ISCSalesforcePackage");

        // System.out.println("new data source id: " + newDataSourceId);
        // JSONArray dataSourceItems = app.GetDataSourceItems(5);
        // app.ImportDataSchemaDefinitions(iris, 5, dataSourceItems);
        app.PublishDataSchemaDefinitions(iris, 5);
    }

    public String DuplicateSalesforceDataSource(IRIS iris, int dataSourceId, String newDataSourceName) {
        String newDataSourceId = "";

        try {
            IRISObject originalDataSource = (IRISObject) iris.classMethodObject("SDS.DataLoader.DS.DataSource", "%OpenId", dataSourceId);

            IRISObject newDataSource = (IRISObject) originalDataSource.invoke("%ConstructClone", 0);

            newDataSource.set("Name", newDataSourceName);

            Object sc = newDataSource.invoke("%Save");

            newDataSourceId = (String) newDataSource.invoke("%Id");
        }

        catch (Exception e) {
            e.printStackTrace();
        }

        return newDataSourceId;

    }

    public void PublishDataSchemaDefinitions(IRIS iris, int dataSourceId) {

        try {
            IRISObject definitionIds = (IRISObject) iris.classMethodObject("%Library.ListOfDataTypes", "%New");

            String query = "SELECT ID FROM SDS_DataCatalog.DataSchemaDefinition WHERE DataSource = ?";
            IRISObject statement = (IRISObject) iris.classMethodObject("%SQL.Statement", "%New");
            Long tsc = (Long) statement.invoke("%Prepare", query);
            IRISObject rset = (IRISObject) statement.invoke("%Execute", dataSourceId);

            Long nextResult = (Long) rset.invoke("%Next");
            boolean hasNext = nextResult != 0;
            while (hasNext) {
                String id = (String) rset.invoke("%GetData", 1);

                System.out.println("ID: " + id);

                nextResult = (Long) rset.invoke("%Next");
                hasNext = nextResult != 0;
            }
        }

        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void ImportDataSchemaDefinitions(IRIS iris, int dataSourceId, JSONArray itemsArray) {

        try {
            IRISObject importList = (IRISObject) iris.classMethodObject("intersystems.dataLoader.v1.dataSources.DataSourceImportList", "%New");
            IRISObject importListItems = (IRISObject) importList.get("items");

            for (int i = 0; i < itemsArray.length(); i++) {
                JSONObject item = itemsArray.getJSONObject(i);
                System.out.println("Item " + (i + 1) + ": " + item.toString());

                IRISObject importListItem = (IRISObject) iris.classMethodObject("intersystems.dataLoader.v1.dataSources.DataSourceImportListItem", "%New");
                importListItem.set("entityName", item.getString("memberName"));

                importListItems.invoke("Insert", importListItem);
            }

            IRISObject dataCatalogService = (IRISObject) iris.classMethodObject("SDS.DataCatalog.BS.Service", "%New", "Data Catalog Service");

            for (int i = 0; i < itemsArray.length(); i++) {
                JSONObject item = itemsArray.getJSONObject(i);

                IRISObject importRequest = (IRISObject) iris.classMethodObject("SDS.DataCatalog.BO.ImportRequest", "%New");
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
