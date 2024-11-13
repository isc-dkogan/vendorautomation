package com.intersystems.vendorautomation;

import java.net.HttpURLConnection;

import com.intersystems.jdbc.IRIS;
import com.intersystems.jdbc.IRISConnection;
import com.intersystems.jdbc.IRISDataSource;
import com.intersystems.jdbc.IRISObject;

import java.io.*;
import java.lang.annotation.Target;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.Yaml;


public class App {

    private Set<String> groups;
    private Set<String> tables;
    private Map<String, List<String>> groupTableMapping;
    private Map<String, String> tableIds = new HashMap<>();
    private Map<String, String> tableGuids = new HashMap<>();
    private Map<String, List<String>> tableFields = new HashMap<>();
    private Map<String, String> recipeGuids = new HashMap<>();

    private IRISConnection connection;
    private IRIS iris;

    private int dataSourceId;
    private String dataSourceType;

    public static void main(String[] args) throws Exception {
        System.out.println("Hello, World!");

        App app = new App();

        app.SetMapping();

        app.connectToIRIS();

        int newDataSourceId = app.duplicateDataSource(Integer.parseInt(args[0]));
        app.SetDataSourceId(newDataSourceId);

        JSONArray dataSourceItems = app.getDataSourceItems();
        app.importDataSchemaDefinitions(dataSourceItems);
        app.publishDataSchemaDefinitions();
        app.setDataSchemaDefinitionInformation();
        app.createRecipes();
        boolean tablePopulated = app.waitForSchedulableResourceTablePopulation();
        if (tablePopulated) {
            app.createScheduledTasks();
        }

        // XMLProcessor xmlProcessor = new XMLProcessor("Salesforce", "src/files/allclasses.xml", Arrays.asList("Staging"));

        // app.createDataSchemaDefinitionYAMLs();
        // app.createRecipeYAMLs();
    }

    private void SetMapping() {
        System.out.println("SetMapping()");

        ExcelReader excelReader = new ExcelReader("src/files/mapping.xlsx");
        groups = excelReader.getUniqueGroupNames();
        tables = excelReader.getAllTableNames();
        groupTableMapping = excelReader.getGroupTableMap();
    }

    private void connectToIRIS() throws Exception {
        System.out.println("connectToIRIS()");

        IrisDatabaseConnection conn = new IrisDatabaseConnection();
        IRISDataSource dataSource = conn.createDataSource();
        connection = (IRISConnection) dataSource.getConnection();
        iris = IRIS.createIRIS(connection);
    }

    private void SetDataSourceId(int dataSourceId) {
        System.out.println("SetDataSourceId()");

        this.dataSourceId = dataSourceId;
    }

    public int duplicateDataSource(int dataSourceId) {
        System.out.println("duplicateDataSource()");

        int newDataSourceId = 0;
        try {
            IRISObject originalDataSource = (IRISObject) iris.classMethodObject("SDS.DataLoader.DS.DataSource", "%OpenId", dataSourceId);

            IRISObject newDataSource = (IRISObject) originalDataSource.invoke("%ConstructClone", 0);

            this.dataSourceType = ((String) newDataSource.invoke("%GetParameter", "DATASOURCENAME")).split(" ")[0];
            newDataSource.set("Name", "ISC" + this.dataSourceType + "PackageSource");

            Long sc = (Long) newDataSource.invoke("%Save");

            String id = (String) newDataSource.invoke("%Id");

            newDataSourceId = Integer.parseInt(id);
        }

        catch (Exception e) {
            e.printStackTrace();
        }

        return newDataSourceId;
    }

    public JSONArray getDataSourceItems() {
        System.out.println("getDataSourceItems()");

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

    public void importDataSchemaDefinitions(JSONArray itemsArray) {
        System.out.println("importDataSchemaDefinitions()");

        try {
            IRISObject dataCatalogService = (IRISObject) iris.classMethodObject("SDS.DataCatalog.BS.Service", "%New", "Data Catalog Service");

            // for (int i = 0; i < itemsArray.length(); i++) {
            for (int i = 0; i < 5; i++) {
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

    public void setDataSchemaDefinitionInformation() {
        System.out.println("setDataSchemaDefinitionInformation()");

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

    public void publishDataSchemaDefinitions() {
        System.out.println("publishDataSchemaDefinitions()");

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

    public void createRecipes() {
        System.out.println("createRecipes()");

        try {
            // IRISObject recipeGroupCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipeGroup.v1.recipeGroupCreate", "%New");
            // recipeGroupCreateObj.set("groupName", "ISCSalesforcePackageRecipes");
            // iris.classMethodObject("SDS.API.RecipeGroupAPI", "RecipeGroupCreate", recipeGroupCreateObj);

            for (String group : groups) {
                System.out.println(group);
                IRISObject recipeCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.recipe.RecipeCreate", "%New");
                recipeCreateObj.set("name", group);
                recipeCreateObj.set("shortName", group);
                // recipeCreateObj.set("groupId", );

                IRISObject recipeCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.recipe.RecipeCreateResponse", "%New");
                recipeCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "RecipeCreate", recipeCreateObj);

                recipeGuids.put(group, (String) recipeCreateRespObj.get("recipeGUID"));

                IRISObject stagingActivityCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityCreate", "%New");
                stagingActivityCreateObj.set("name", "StagingActivity");
                stagingActivityCreateObj.set("shortName", "SA");
                stagingActivityCreateObj.set("dataSourceId", dataSourceId);

                IRISObject stagingActivityCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityCreateResponse", "%New");
                stagingActivityCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "StagingActivityCreate", recipeCreateRespObj.get("id"), stagingActivityCreateObj);

                IRISObject stagingActivityUpdateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityUpdate", "%New");
                stagingActivityUpdateObj.set("name", stagingActivityCreateRespObj.get("name"));
                stagingActivityUpdateObj.set("saveVersion", 1);

                IRISObject promotionActivityCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.promotion.PromotionActivityCreate", "%New");
                promotionActivityCreateObj.set("name", "PromotionActivity");
                promotionActivityCreateObj.set("runOrder", 1);
                promotionActivityCreateObj.set("promotionType", "Internal");

                IRISObject promotionActivityCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.promotion.PromotionActivityCreateResponse", "%New");
                promotionActivityCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "PromotionActivityCreate", recipeCreateRespObj.get("id"), promotionActivityCreateObj);

                List<String> groupTables = groupTableMapping.get(group);
                IRISObject dataSchemas = (IRISObject) iris.classMethodObject("%Library.ListOfObjects", "%New");
                for (int i = 0; i < groupTables.size(); i++) {
                    String table = groupTables.get(i);

                    IRISObject stagingActivityUpdateItemObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityUpdateItem", "%New");

                    stagingActivityUpdateItemObj.set("id", tableIds.get(table));

                    List<String> fields = tableFields.get(table);
                    if (fields == null) {
                        // System.out.println("null fields for table: " + table);
                        continue;
                    }
                    IRISObject dataSchemaFields = (IRISObject) iris.classMethodObject("%Library.ListOfObjects", "%New");
                    List<String> fieldList = new ArrayList<>();
                    List<String> modifiedFieldList = new ArrayList<>();
                    for (int j = 0; j < fields.size(); j++) {
                        String field = fields.get(j);

                        fieldList.add(field);
                        if (iris.classMethodBoolean("%SYSTEM.SQL", "IsReservedWord", field)) {
                            modifiedFieldList.add("\"" + field + "\"");
                        }
                        else {
                            modifiedFieldList.add(field);
                        }

                        IRISObject stagingActivityItemUpdateItemObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityItemUpdateItem", "%New");
                        stagingActivityItemUpdateItemObj.set("name", field);
                        stagingActivityItemUpdateItemObj.set("selected", true);

                        dataSchemaFields.invoke("Insert", stagingActivityItemUpdateItemObj);
                    }
                    stagingActivityUpdateItemObj.set("dataSchemaFields", dataSchemaFields);
                    stagingActivityUpdateItemObj.set("selected", true);

                    dataSchemas.invoke("Insert", stagingActivityUpdateItemObj);

                    String modifiedRecipeName = iris.classMethodBoolean("%SYSTEM.SQL", "IsReservedWord", group) ? "\"" + group + "\"" : group;
                    String modifiedTableName =iris.classMethodBoolean("%SYSTEM.SQL", "IsReservedWord", table) ? "\"" + table + "\"" : table;

                    String updateSqlExpression = String.format(
                    "UPDATE ISC_%s_%s.%s tt\n" +
                    "SET UpdateTimestamp = CURRENT_TIMESTAMP, UpdateUser = USER\n" +
                    "FROM {sa}.%s st WHERE st.%%BatchId={%%BatchId}",
                    dataSourceType, modifiedRecipeName, modifiedTableName, modifiedTableName
                    );

                    Map<String, Object> promotionInsertItem = new HashMap<>();
                    promotionInsertItem.put("createdBy", "");
                    String fieldListString = String.join(", ", modifiedFieldList);
                    String insertSqlExpression = String.format(
                                                                "INSERT ISC_%s_%s.%s(%s)\n" +
                                                                "SELECT %s\n" +
                                                                "FROM {sa}.%s ta WHERE %%BatchId={%%BatchId}",
                                                                dataSourceType, modifiedRecipeName, modifiedTableName, fieldListString, fieldListString, modifiedTableName
                                                        );

                    IRISObject updatePromotionActivityItemCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.promotion.PromotionActivityItemCreate", "%New");
                    updatePromotionActivityItemCreateObj.set("description", table + " Update");
                    updatePromotionActivityItemCreateObj.set("sqlExpression", updateSqlExpression);
                    updatePromotionActivityItemCreateObj.set("activitySaveVersion", (i + 2)*2);
                    updatePromotionActivityItemCreateObj.set("runOrder", (i+1)*10);

                    IRISObject updatePromotionActivityItemCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.promotion.PromotionActivityItemCreateResponse", "%New");
                    updatePromotionActivityItemCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "PromotionActivityItemCreate", promotionActivityCreateRespObj.get("id"), updatePromotionActivityItemCreateObj);

                    IRISObject insertPromotionActivityItemCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.promotion.PromotionActivityItemCreate", "%New");
                    insertPromotionActivityItemCreateObj.set("description", table + " Insert");
                    insertPromotionActivityItemCreateObj.set("sqlExpression", insertSqlExpression);
                    insertPromotionActivityItemCreateObj.set("activitySaveVersion", (i + 3)*2);
                    insertPromotionActivityItemCreateObj.set("runOrder", (i+1)*10 + 1);

                    IRISObject insertPromotionActivityItemCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.promotion.PromotionActivityItemCreateResponse", "%New");
                    insertPromotionActivityItemCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "PromotionActivityItemCreate", promotionActivityCreateRespObj.get("id"), insertPromotionActivityItemCreateObj);
                }
                stagingActivityUpdateObj.set("dataSchemas", dataSchemas);

                IRISObject stagingActivityUpdateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityUpdateResponse", "%New");
                stagingActivityUpdateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "StagingActivityUpdate", stagingActivityCreateRespObj.get("id"), stagingActivityUpdateObj);

                // IRISObject stagingActivity = (IRISObject) iris.classMethodObject("SDS.DataLoader.Staging.StagingActivity", "%OpenId", stagingActivityUpdateRespObj.get("id"));
                // stagingActivity.set("Editing", false);
                // Long sc = (Long) stagingActivity.invoke("%Save");

                // IRISObject stagingActivitySessionCloseRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivitySessionResponse", "%New");
                // stagingActivitySessionCloseRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "StagingActivitySessionClose", stagingActivityUpdateRespObj.get("id"), true, false);
            }
        }

        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean waitForSchedulableResourceTablePopulation() {
        System.out.println("waitForSchedulableResourceTablePopulation()");

        boolean tableUpdated = false;
        long timeout = 10000;
        long startTime = System.currentTimeMillis();

        try {
            String query = "Select GUID From SDS_BusinessScheduler.SchedulableResource";
            PreparedStatement stmt = connection.prepareStatement(query);

            while (!tableUpdated && (System.currentTimeMillis() - startTime) < timeout) {

                ResultSet rs = stmt.executeQuery();

                List<String> guids = new ArrayList<>();
                while (rs.next()) {
                    String guid = rs.getString("GUID");

                    guids.add(guid);
                }

                tableUpdated = guids.containsAll(tableGuids.values());
                Thread.sleep(500);
            }

            stmt.close();
        }

        catch (Exception e) {
            e.printStackTrace();
        }

        if (!tableUpdated) {
            System.out.println("Timeout reached before SchedulableResource table populated with new recipes. Cannot create Business Scheduler Tasks.");
        }

        return tableUpdated;
    }

    public void createScheduledTasks() {
        System.out.println("createScheduledTasks()");

        IRISObject scheduledTaskGroupCreateObj = (IRISObject) iris.classMethodObject("intersystems.businessScheduler.v1.scheduledTask.ScheduledTaskCreate", "%New");
        scheduledTaskGroupCreateObj.set("enabled", true);
        scheduledTaskGroupCreateObj.set("taskDescription", "ISC" + dataSourceType + "PackageTaskGroup");
        scheduledTaskGroupCreateObj.set("scheduledTaskType", "1");
        scheduledTaskGroupCreateObj.set("schedulingType", "1");

        IRISObject scheduledTaskGroupCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.businessScheduler.v1.scheduledTask.ScheduledTaskCreateResponse", "%New");
        scheduledTaskGroupCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.BusinessSchedulerAPI", "ScheduledTaskCreate", scheduledTaskGroupCreateObj);

        for (String group : groups) {
            IRISObject scheduledTaskCreateObj = (IRISObject) iris.classMethodObject("intersystems.businessScheduler.v1.scheduledTask.ScheduledTaskCreate", "%New");
            scheduledTaskCreateObj.set("enabled", true);
            scheduledTaskCreateObj.set("entityId", 1);
            scheduledTaskCreateObj.set("exceptionWorkflowRole", "System Administrator");
            scheduledTaskCreateObj.set("dependencyInactivityTimeout", 300);
            scheduledTaskCreateObj.set("taskDescription", group);
            scheduledTaskCreateObj.set("scheduledTaskType", "0");
            scheduledTaskCreateObj.set("schedulingType", "1");
            scheduledTaskCreateObj.set("schedulingGroup", scheduledTaskGroupCreateRespObj.get("id"));
            scheduledTaskCreateObj.set("errorEmailDistributionListId", 0);
            scheduledTaskCreateObj.set("successEmailDistributionListId", 0);
            scheduledTaskCreateObj.set("errorEmailTemplateId", 1);
            scheduledTaskCreateObj.set("successEmailTemplateId", 2);
            scheduledTaskCreateObj.set("taskDefinitionClassName", "SDS.DataLoader.RunRecipeTaskDefinition");
            scheduledTaskCreateObj.set("scheduledResourceGUID", recipeGuids.get(group));

            IRISObject scheduledTaskCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.businessScheduler.v1.scheduledTask.ScheduledTaskCreateResponse", "%New");
            scheduledTaskCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.BusinessSchedulerAPI", "ScheduledTaskCreate", scheduledTaskCreateObj);
        }
    }

    public void createDataSchemaDefinitionYAMLs() throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(FlowStyle.BLOCK);

        Yaml yaml = new Yaml(options);

        Map<String, Object> data = new HashMap<>();
        data.put("apiVersion", "v1");
        data.put("kind", "TotalViewDataSchemaDefinition");

        // Metadata map
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("version", 1);
        data.put("metadata", metadata);

        // Spec map
        Map<String, Object> spec = new HashMap<>();
        int tableNum = 1;
        int limit = 10;
        int count = 0;
        for (String table : tables) {
            count++;
            if (count >= limit) {
                break;
            }
            spec.put("name", "ISC" + this.dataSourceType + "Package" + "-" + table);
            spec.put("dataSourceItemName", table);
            spec.put("extractionStrategy", "Simple Load");
            spec.put("extractionStrategyField", "");
            spec.put("ObjectName", table);
            spec.put("catalogDescription", "");
            List<String> primaryKeyFieldList = new ArrayList<>();
            spec.put("primaryKeyFieldList", primaryKeyFieldList);
            spec.put("entityName", table);
            spec.put("dataSource", "ISC" + this.dataSourceType + "Package");

            List<Map<String, Object>> fields = new ArrayList<>();

            IRISObject businessOperation = (IRISObject) iris.classMethodObject("SDS.DataLoader.BO.SalesForce.Operation", "%New", "EmailNotificationTLSConfig");
            IRISObject memberDefResponseObj = (IRISObject) iris.classMethodObject("SDS.DataLoader.BO.DataSource.V1.Response.MemberDefResponse", "%New");

            IRISObject dataSource = (IRISObject) iris.classMethodObject("SDS.DataLoader.DS.DataSource", "%OpenId", dataSourceId);
            businessOperation.set("SSLConfig", "EmailNotificationTLSConfig");
            memberDefResponseObj = (IRISObject) businessOperation.invoke("GenerateMemberDefFromObject", dataSource, table);

            IRISObject columns = (IRISObject) iris.classMethodObject("%Library.ListOfObjects", "%New");
            columns = (IRISObject) memberDefResponseObj.get("Columns");

            for (int i = 1; i < ((Long) columns.invoke("Count")).intValue(); i++) {
                IRISObject column = (IRISObject) columns.invoke("GetAt", i);

                Map<String, Object> field = new HashMap<>();
                field.put("fieldName", column.get("FieldName"));
                field.put("fieldType", column.get("FieldType"));
                field.put("minVal", column.get("Minval"));
                field.put("maxVal", column.get("Maxval"));
                field.put("scale", column.get("Scale"));
                field.put("required", column.get("Required"));
                field.put("length", column.get("Length"));
                field.put("defaultValue", column.get("DefaultValue"));
                field.put("dataFormat", column.get("DataFormat"));
                field.put("srcDataType", column.get("SrcDataType"));
                field.put("fieldDescription", column.get("FieldDescription"));

                fields.add(field);
            }

            spec.put("fields", fields);

            data.put("spec", spec);

            try (FileWriter writer = new FileWriter("schemadefs/"+table+".TotalViewDataSchemaDefinition")) {
                yaml.dump(data, writer);
                System.out.println("Created file #" + tableNum + " schemadefs/"+table+".TotalViewDataSchemaDefinition");
                tableNum ++;
            }

            catch (IOException e) {
                e.printStackTrace();
            }
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
            spec.put("groupName", "ISC" + this.dataSourceType + "Package");

            List<Map<String, Object>> stagingActivities = new ArrayList<>();
            Map<String, Object> stagingActivity = new HashMap<>();
            stagingActivity.put("dataSourceName", "ISC" + this.dataSourceType + "PackageSource");
            stagingActivity.put("createUser", "");
            stagingActivity.put("disableUser", "");
            stagingActivity.put("name", "StagingActivity");
            stagingActivity.put("shortName", "sa");

            List<Map<String, Object>> stagingItems = new ArrayList<>();

            List<Map<String, Object>> promotionActivities = new ArrayList<>();
            Map<String, Object> promotionActivity = new HashMap<>();
            promotionActivity.put("name", "Promotion Activity");
            promotionActivity.put("runOrder", 1);
            promotionActivity.put("createUser", "");
            promotionActivity.put("disableUser", "");
            promotionActivity.put("promotionType", "Internal");
            promotionActivity.put("targetConnection", "");

            List<Map<String, Object>> promotionItems = new ArrayList<>();

            List<String> groupTables = groupTableMapping.get(group);
            for (int i = 0; i < groupTables.size(); i++) {
                String table = groupTables.get(i);

                Map<String, Object> stagingItem = new HashMap<>();
                stagingItem.put("dataSchemaDefinition", tableGuids.get(table));
                stagingItem.put("customTargetTable", "");
                stagingItem.put("sqlQuickLoad", 1);
                stagingItem.put("actionOnDroppedRecords", "");
                stagingItem.put("maxDroppedRecords", "");
                stagingItem.put("removeFileWhenDoneReading", "0");

                List<String> fields = tableFields.get(table);
                if (fields == null) {
                    // System.out.println("null fields for table: " + table);
                    continue;
                }

                List<String> fieldList = new ArrayList<>();
                List<String> modifiedFieldList = new ArrayList<>();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.get(j);

                    fieldList.add(field);
                    if (iris.classMethodBoolean("%SYSTEM.SQL", "IsReservedWord", field)) {
                        modifiedFieldList.add("\"" + field + "\"");
                    }
                    else {
                        modifiedFieldList.add(field);
                    }
                }

                stagingItem.put("fieldList", fieldList);
                stagingItems.add(stagingItem);

                String modifiedRecipeName = iris.classMethodBoolean("%SYSTEM.SQL", "IsReservedWord", group) ? "\"" + group + "\"" : group;
                String modifiedTableName =iris.classMethodBoolean("%SYSTEM.SQL", "IsReservedWord", table) ? "\"" + table + "\"" : table;
                Map<String, Object> promotionUpdateItem = new HashMap<>();
                promotionUpdateItem.put("createdBy", "");
                String updateSqlExpression = String.format(
                    "UPDATE ISC_%s_%s.%s tt\n" +
                    "SET UpdateTimestamp = CURRENT_TIMESTAMP, UpdateUser = USER\n" +
                    "FROM {sa}.%s st WHERE st.%%BatchId={%%BatchId}",
                    dataSourceType, modifiedRecipeName, modifiedTableName, modifiedTableName
                );
                promotionUpdateItem.put("sqlExpression", updateSqlExpression);
                promotionUpdateItem.put("description", table + " Update");
                promotionUpdateItem.put("runOrder", (i+1)*10);

                Map<String, Object> promotionInsertItem = new HashMap<>();
                promotionInsertItem.put("createdBy", "");
                String fieldListString = String.join(", ", modifiedFieldList);
                String insertSqlExpression = String.format(
                                                            "INSERT ISC_%s_%s.%s(%s)\n" +
                                                            "SELECT %s\n" +
                                                            "FROM {sa}.%s ta WHERE %%BatchId={%%BatchId}",
                                                            dataSourceType, modifiedRecipeName, modifiedTableName, fieldListString, fieldListString, modifiedTableName
                                                    );
                promotionInsertItem.put("sqlExpression", insertSqlExpression);
                promotionInsertItem.put("description", table + " Insert");
                promotionInsertItem.put("runOrder", (i+1)*10 + 1);

                promotionItems.add(promotionUpdateItem);
                promotionItems.add(promotionInsertItem);
            }

            stagingActivity.put("items", stagingItems);
            stagingActivities.add(stagingActivity);
            promotionActivity.put("items", promotionItems);
            promotionActivities.add(promotionActivity);

            spec.put("stagingActivities", stagingActivities);
            spec.put("promotionActivities", promotionActivities);

            data.put("spec", spec);

            try (FileWriter writer = new FileWriter("recipes/"+group+".TotalViewRecipe")) {
                yaml.dump(data, writer);
            }

            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void createScheduledTaskYAMLs() throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(FlowStyle.BLOCK);

        for (String group : groups) {
            Yaml yaml = new Yaml(options);

            Map<String, Object> data = new HashMap<>();
            data.put("apiVersion", "v1");
            data.put("kind", "TotalViewScheduledTask");

            // Metadata map
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("version", 1);
            data.put("metadata", metadata);

            // Spec map
            Map<String, Object> spec = new HashMap<>();
            spec.put("owner", "");
            spec.put("taskDescription", group);
            spec.put("schedulingType", "Manually Run");
            spec.put("enabled", 1);
            spec.put("scheduledTaskType", "Task");
            Map<String, Object> schedulableResource = new HashMap<>();
            schedulableResource.put("type", "Recipe");
            schedulableResource.put("identifier", "");
            spec.put("schedulableResource", schedulableResource);
            spec.put("entity", "");
            spec.put("exceptionWorkflowRole", "");
            spec.put("businessEventTag", "ISC" + dataSourceType + "PackageTaskGroup");
            spec.put("dependencyInactivityTimeout", "");
            spec.put("schedulingGroup", "");
            Map<String, Object> schedulingProperties = new HashMap<>();
            schedulingProperties.put("dependencyInactivityTimeout", 300);
            spec.put("schedulingProperties", schedulingProperties);

            try (FileWriter writer = new FileWriter("scheduledtasks/"+group+".TotalViewScheduledTask")) {
                yaml.dump(data, writer);
            }
        }

    }
}
