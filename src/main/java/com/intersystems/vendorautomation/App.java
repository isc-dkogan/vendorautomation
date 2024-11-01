package com.intersystems.vendorautomation;

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
import java.sql.Statement;
import java.util.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;


public class App {

    private Set<String> groups;
    private Set<String> tables;
    private Map<String, List<String>> groupTableMapping;

    private IRISConnection connection;
    private IRIS iris;

    private int dataSourceId = 3;

    private Map<String, String> tableIds = new HashMap<>();
    private Map<String, String> tableGuids = new HashMap<>();
    private Map<String, List<String>> tableFields = new HashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println("Hello, World!");

        App app = new App();

        app.SetMapping();

        app.ConnectToIRIS();

        // int newDataSourceId = app.DuplicateDataSource(2, "ISCSalesforcePackage");
        // app.SetDataSourceId(newDataSourceId);

        // JSONArray dataSourceItems = app.GetDataSourceItems();
        // app.ImportDataSchemaDefinitions(dataSourceItems);
        // app.PublishDataSchemaDefinitions();
        app.SetDataSchemaDefinitionInformation();
        // app.CreateRecipes();
        app.createRecipeYAMLs();
    }

    private void SetMapping() {
        System.out.println("SetMapping()");

        ExcelReader excelReader = new ExcelReader("src/files/mapping.xlsx");
        groups = excelReader.getUniqueGroupNames();
        tables = excelReader.getAllTableNames();
        groupTableMapping = excelReader.getGroupTableMap();
    }

    private void ConnectToIRIS() throws Exception {
        System.out.println("ConnectToIRIS()");

        IrisDatabaseConnection conn = new IrisDatabaseConnection();
        IRISDataSource dataSource = conn.createDataSource();
        connection = (IRISConnection) dataSource.getConnection();
        iris = IRIS.createIRIS(connection);
    }

    private void SetDataSourceId(int dataSourceId) {
        System.out.println("SetDataSourceId()");

        this.dataSourceId = dataSourceId;
    }

    public int DuplicateDataSource(int dataSourceId, String newDataSourceName) {
        System.out.println("DuplicateDataSource()");

        int newDataSourceId = 0;
        try {
            IRISObject originalDataSource = (IRISObject) iris.classMethodObject("SDS.DataLoader.DS.DataSource", "%OpenId", dataSourceId);

            IRISObject newDataSource = (IRISObject) originalDataSource.invoke("%ConstructClone", 0);

            newDataSource.set("Name", newDataSourceName);

            Long sc = (Long) newDataSource.invoke("%Save");

            String id = (String) newDataSource.invoke("%Id");

            newDataSourceId = Integer.parseInt(id);
        }

        catch (Exception e) {
            e.printStackTrace();
        }

        return newDataSourceId;
    }

    public JSONArray GetDataSourceItems() {
        System.out.println("GetDataSourceItems()");

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

    public void ImportDataSchemaDefinitions(JSONArray itemsArray) {
        System.out.println("ImportDataSchemaDefinitions()");

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
                importRequest.set("SendAsync", false);

                Long sc = (Long) dataCatalogService.invoke("ProcessInput", importRequest);
            }

        }

        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void SetDataSchemaDefinitionInformation() {
        System.out.println("SetDataSchemaDefinitionInformation()");

        try {
            String query = "SELECT dsd.ID, dsd.AssignedGUID, dsd.DataSourceItemName, dsf.FieldName \n" +
                            "FROM SDS_DataCatalog.DataSchemaDefinition AS dsd \n" +
                            "JOIN SDS_DataCatalog.DataSchemaField AS dsf \n" +
                            "ON dsd.ID = dsf.DataSchema \n" +
                            "WHERE dsd.DataSource = ?";


            PreparedStatement stmt = connection.prepareStatement(query);
            stmt.setInt(1, dataSourceId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String id = rs.getString("ID");
                String guid = rs.getString("AssignedGUID");
                String table = rs.getString("DataSourceItemName");
                String field = rs.getString("FieldName");

                tableIds.put(table, id);
                tableGuids.put(table, guid);
                tableFields.computeIfAbsent(table, k -> new ArrayList<>()).add(field);
            }
            stmt.close();
        }

        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void PublishDataSchemaDefinitions() {
        System.out.println("PublishDataSchemaDefinitions()");

        int count = 0;
        try {
            String query = "SELECT ID FROM SDS_DataCatalog.DataSchemaDefinition WHERE DataSource = ?";

            PreparedStatement stmt = connection.prepareStatement(query);
            stmt.setInt(1, dataSourceId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String id = rs.getString("ID");

                IRISObject schemaDefinitionSessionCloseRespObj = (IRISObject) iris.classMethodObject("intersystems.dataCatalog.v1.browser.DataSchemaDefinitionSessionResponse", "%New");
                schemaDefinitionSessionCloseRespObj = (IRISObject) iris.classMethodObject("SDS.API.DataCatalogAPI", "SchemaDefinitionSessionClose", id, 1);

                count += 1;
            }
            System.out.println("Published " + count + " data schema definitions");
            stmt.close();
        }

        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void CreateRecipes() {
        System.out.println("CreateRecipes()");

        try {
            // IRISObject recipeGroupCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipeGroup.v1.recipeGroupCreate", "%New");
            // recipeGroupCreateObj.set("groupName", "ISCSalesforcePackageRecipes");
            // iris.classMethodObject("SDS.API.RecipeGroupAPI", "RecipeGroupCreate", recipeGroupCreateObj);

            for (String group : groups) {
                IRISObject recipeCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.recipe.RecipeCreate", "%New");
                recipeCreateObj.set("name", group);
                recipeCreateObj.set("shortName", group);
                // recipeCreateObj.set("groupId", );

                IRISObject recipeCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.recipe.RecipeCreateResponse", "%New");
                recipeCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "RecipeCreate", recipeCreateObj);

                IRISObject stagingActivityCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityCreate", "%New");
                stagingActivityCreateObj.set("name", "StagingActivity");
                stagingActivityCreateObj.set("shortName", "SA");
                stagingActivityCreateObj.set("dataSourceId", dataSourceId);

                IRISObject stagingActivityCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityCreateResponse", "%New");
                stagingActivityCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "StagingActivityCreate", recipeCreateRespObj.get("id"), stagingActivityCreateObj);

                IRISObject stagingActivityUpdateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityUpdate", "%New");
                stagingActivityUpdateObj.set("name", stagingActivityCreateRespObj.get("name"));
                stagingActivityUpdateObj.set("saveVersion", 1);

                List<String> groupTables = groupTableMapping.get(group);
                IRISObject dataSchemas = (IRISObject) iris.classMethodObject("%Library.ListOfObjects", "%New");
                for (int i = 0; i < groupTables.size(); i++) {
                    String table = groupTables.get(i);

                    IRISObject stagingActivityUpdateItemObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityUpdateItem", "%New");

                    stagingActivityUpdateItemObj.set("id", tableIds.get(table));

                    List<String> fields = tableFields.get(table);
                    if (fields == null) {
                        System.out.println("null fields for table: " + table);
                        continue;
                    }
                    IRISObject dataSchemaFields = (IRISObject) iris.classMethodObject("%Library.ListOfObjects", "%New");
                    for (int j = 0; j < fields.size(); j++) {
                        String field = fields.get(j);

                        IRISObject stagingActivityItemUpdateItemObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityItemUpdateItem", "%New");
                        stagingActivityItemUpdateItemObj.set("name", field);
                        stagingActivityItemUpdateItemObj.set("selected", true);

                        dataSchemaFields.invoke("Insert", stagingActivityItemUpdateItemObj);
                    }
                    stagingActivityUpdateItemObj.set("dataSchemaFields", dataSchemaFields);
                    stagingActivityUpdateItemObj.set("selected", true);

                    dataSchemas.invoke("Insert", stagingActivityUpdateItemObj);
                }
                stagingActivityUpdateObj.set("dataSchemas", dataSchemas);

                IRISObject stagingActivityUpdateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityUpdateResponse", "%New");
                stagingActivityUpdateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "StagingActivityUpdate", stagingActivityCreateRespObj.get("id"), stagingActivityUpdateObj);

                System.out.println("id: " + stagingActivityUpdateRespObj.get("id"));

                // IRISObject stagingActivity = (IRISObject) iris.classMethodObject("SDS.DataLoader.Staging.StagingActivity", "%OpenId", stagingActivityUpdateRespObj.get("id"));
                // stagingActivity.set("Editing", false);
                // Long sc = (Long) stagingActivity.invoke("%Save");

                IRISObject stagingActivitySessionCloseRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivitySessionResponse", "%New");
                stagingActivitySessionCloseRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "StagingActivitySessionClose", stagingActivityUpdateRespObj.get("id"), true, false);
            }
        }

        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void createRecipeYAMLs() throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        for (String group : groups) {
            Yaml yaml = new Yaml(options);

            Map<String, Object> data = new HashMap<>();
            data.put("apiVersion", "v1");
            data.put("kind", "TotalViewRecipe");

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("guid", "");
            metadata.put("version", "1");

            data.put("metadata", metadata);

            Map<String, Object> spec = new HashMap<>();
            spec.put("name", group);
            spec.put("shortName", group);
            spec.put("recordStepModeActive", "");
            spec.put("maxRows", "0");
            spec.put("recipeActiveStatus", "Active");
            spec.put("groupName", "");

            List<Map<String, Object>> stagingActivities = new ArrayList<>();
            Map<String, Object> stagingActivity = new HashMap<>();
            stagingActivity.put("dataSourceName", "");
            stagingActivity.put("createUser", "");
            stagingActivity.put("disableUser", "");
            stagingActivity.put("name", "StagingActivity");
            stagingActivity.put("shortName", "SA");

            List<Map<String, Object>> items = new ArrayList<>();

            List<String> groupTables = groupTableMapping.get(group);
            for (int i = 0; i < groupTables.size(); i++) {
                String table = groupTables.get(i);

                Map<String, Object> item = new HashMap<>();
                item.put("dataSchemaDefinition", tableGuids.get(table));
                item.put("customTargetTable", "");
                item.put("sqlQuickLoad", "1");
                item.put("actionOnDroppedRecords", "");
                item.put("maxDroppedRecords", "");
                item.put("removeFileWhenDoneReading", "0");

                List<String> fields = tableFields.get(table);
                if (fields == null) {
                    System.out.println("null fields for table: " + table);
                    continue;
                }

                List<String> fieldList = new ArrayList<>();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.get(j);

                    fieldList.add(field);
                }

                item.put("fieldList", fieldList);
                items.add(item);
            }

            stagingActivity.put("items", items);
            stagingActivities.add(stagingActivity);

            spec.put("stagingActivities", stagingActivities);

            data.put("spec", spec);

            try (FileWriter writer = new FileWriter(group+"Recipe.yaml")) {
                yaml.dump(data, writer);
            }
        }
    }
}
