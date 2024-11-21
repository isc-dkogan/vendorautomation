package com.intersystems.vendorautomation;

import java.net.HttpURLConnection;

import com.intersystems.jdbc.IRIS;
import com.intersystems.jdbc.IRISConnection;
import com.intersystems.jdbc.IRISDataSource;
import com.intersystems.jdbc.IRISObject;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;


public class App {

    private Set<String> groups;
    private Set<String> tables;
    private Map<String, List<String>> groupTableMapping;
    private Map<String, String> tableIds = new HashMap<>();
    private Map<String, String> tableGuids = new HashMap<>();
    private Map<String, List<String>> tableFields = new HashMap<>();
    private Map<String, String> recipeGuids = new HashMap<>();
    private List<String> recipeIds = new ArrayList<>();
    private String scheduledTaskGroupId;

    private String mappingSourcePath = "src/files/mapping.xlsx";
    private String xmlSourcePath = "src/files/allclasses.xml";

    private IRISConnection connection;
    private IRIS iris;

    private int dataSourceId;
    private String dataSourceType;
    private String schema;

    private static final Logger log = LogManager.getLogger(App.class);
    public static void main(String[] args) throws Exception {
        log.info("Hello, World!");

        App app = new App();

        if (args.length < 1) {
            throw new Exception("Error: Missing arguments. At least 1 argument required");
        }

        if (args.length > 2) {
            throw new Exception("Error: Too many arguments. Please enter at most 2 arguments");
        }

        try {
            String schema = "";

            if (args.length == 2) {
                schema = args[1];
            }


            app.run(args[0], schema);
        }
        catch (Exception e) {
            // app.cleanup();
            log.error("Run failed, ", e);
        }
    }

    private void run(String dataSourceId, String schema) throws Exception {
        setMapping();

        connectToIRIS();

        this.dataSourceId = duplicateDataSource(Integer.parseInt(dataSourceId));
        this.schema = schema;

        JSONArray dataSourceItems = getDataSourceItems();
        importDataSchemaDefinitions(dataSourceItems);
        publishDataSchemaDefinitions();
        setDataSchemaDefinitionInformation();
        createRecipes();
        boolean tablePopulated = waitForSchedulableResourceTablePopulation();
        if (tablePopulated) {
            createScheduledTasks();
        }

        File file = new File(xmlSourcePath);

        if (file.exists()) {
            XMLProcessor xmlProcessor = new XMLProcessor(dataSourceType, xmlSourcePath);
            xmlProcessor.process(Arrays.asList("Staging"));
        }

        // exportBundle();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("Would you like to clean up the artifacts you've created? (yes/no): ");
            String input = scanner.nextLine().trim().toLowerCase();

            if (input.equals("yes")) {
                cleanup();
                break;
            } else if (input.equals("no")) {
                break;
            } else {
                System.out.println("Invalid input. Please type 'yes' or 'no'.");
            }
        }

        scanner.close();
    }

    private void setMapping() {
        log.info("setMapping()");

        ExcelReader excelReader = new ExcelReader(mappingSourcePath);
        groups = excelReader.getUniqueGroupNames();
        tables = excelReader.getAllTableNames();
        groupTableMapping = excelReader.getGroupTableMap();
    }

    private void connectToIRIS() throws Exception {
        log.info("connectToIRIS()");

        IrisDatabaseConnection conn = new IrisDatabaseConnection();
        IRISDataSource dataSource = conn.createDataSource();
        connection = (IRISConnection) dataSource.getConnection();
        iris = IRIS.createIRIS(connection);
    }

    private int duplicateDataSource(int dataSourceId) {
        log.info("duplicateDataSource()");

        int newDataSourceId;
        IRISObject originalDataSource;

        try {
            originalDataSource = (IRISObject) iris.classMethodObject("SDS.DataLoader.DS.DataSource", "%OpenId", dataSourceId);
        }
        catch (Exception e) {
            throw new AppException(String.format("Data source with id %d does not exist", dataSourceId), e);
        }

        IRISObject newDataSource = (IRISObject) originalDataSource.invoke("%ConstructClone", 0);

        this.dataSourceType = ((String) newDataSource.invoke("%GetParameter", "DATASOURCENAME")).split(" ")[0];
        newDataSource.set("Name", String.format("ISC%sPackageSource", dataSourceType));

        Long sc = (Long) newDataSource.invoke("%Save");

        String id = (String) newDataSource.invoke("%Id");

        newDataSourceId = Integer.parseInt(id);

        log.info("New data source created with id = " + newDataSourceId);

        return newDataSourceId;
    }

    public JSONArray getDataSourceItems() throws Exception {
        log.info("getDataSourceItems()");

        JSONArray itemsArray = null;

        boolean supportsSchema;
        IRISObject dataSource = (IRISObject) iris.classMethodObject("SDS.DataLoader.DS.DataSource", "%OpenId", dataSourceId);
        Long capabilitiesCode = (Long) dataSource.invoke("%GetParameter", "DATASOURCECAPABILITIESCODE");
        Long supportsSchemaVal = (Long) dataSource.invoke("%GetParameter", "SUPPORTSSCHEMA");
        supportsSchema = (capabilitiesCode.intValue() & supportsSchemaVal.intValue()) != 0;

        String urlString;
        if (supportsSchema) {
            if (schema == "") {
                throw new Exception("Data source requires schema, but no schema specified.");
            }

            String encodedParamValue = URLEncoder.encode(schema, "UTF-8");
            urlString = String.format("http://localhost:8081/intersystems/data-loader/v1/dataSources/%s/schemas/members?schema=%s",
                    dataSourceId, encodedParamValue);
        }
        else {
            if (schema != "") {
                throw new Exception(String.format("A schema %s was specified but the data source has no schemas.", schema));
            }

            urlString = String.format("http://localhost:8081/intersystems/data-loader/v1/dataSources/%s/schemas/members", dataSourceId);
        }

        URL url = new URL(urlString);
        log.info(String.format("Sending GET request to %s", url));

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        String userCredentials = "systemadmin:sys";
        String basicAuth = "Basic " + new String(Base64.getEncoder().encode(userCredentials.getBytes()));
        connection.setRequestProperty("Authorization", basicAuth);

        int responseCode = connection.getResponseCode();

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

            log.info(String.format("response: %s", jsonObject.toString()));
            if (jsonObject.has("items")) {
                itemsArray = jsonObject.getJSONArray("items");
            } else {
                throw new Exception("No 'items' key found in members response.");
            }
        } else {
            throw new Exception(String.format("Request to  failed with response code %d", responseCode));
        }

        connection.disconnect();

        log.info(String.format("%d items found", itemsArray.length()));

        return itemsArray;
    }

    private void importDataSchemaDefinitions(JSONArray itemsArray) {
        log.info("importDataSchemaDefinitions()");

        IRISObject dataCatalogService = (IRISObject) iris.classMethodObject("SDS.DataCatalog.BS.Service", "%New", "Data Catalog Service");

        for (int i = 0; i < itemsArray.length(); i++) {
            JSONObject item = itemsArray.getJSONObject(i);

            IRISObject importRequest = (IRISObject) iris.classMethodObject("SDS.DataCatalog.BO.ImportRequest", "%New");
            importRequest.set("BatchId", 1);
            importRequest.set("DataSourceId", dataSourceId);
            importRequest.set("MemberName", item.getString("memberName"));
            importRequest.set("SchemaName", schema);
            importRequest.set("SendAsync", false);

            Long sc = (Long) dataCatalogService.invoke("ProcessInput", importRequest);
            log.info(String.format("Imported item %d: %s", i + 1, item.toString()));
        }
    }

    private void setDataSchemaDefinitionInformation() throws SQLException {
        log.info("setDataSchemaDefinitionInformation()");

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

    private void publishDataSchemaDefinitions() throws SQLException {
        log.info("publishDataSchemaDefinitions()");

        int count = 0;

        String query = "SELECT ID FROM SDS_DataCatalog.DataSchemaDefinition WHERE DataSource = ?";

        PreparedStatement stmt = connection.prepareStatement(query);
        stmt.setInt(1, dataSourceId);
        ResultSet rs = stmt.executeQuery();

        while (rs.next()) {
            String id = rs.getString("ID");

            IRISObject schemaDefinitionSessionCloseRespObj = (IRISObject) iris.classMethodObject("intersystems.dataCatalog.v1.browser.DataSchemaDefinitionSessionResponse", "%New");
            schemaDefinitionSessionCloseRespObj = (IRISObject) iris.classMethodObject("SDS.API.DataCatalogAPI", "SchemaDefinitionSessionClose", id, 1);

            count += 1;
            log.info(String.format("Published data schema definition %d", count));
        }
        stmt.close();
    }

    private void createRecipes() {
        log.info("createRecipes()");

        IRISObject recipeGroupCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipeGroup.v1.recipeGroupCreate", "%New");
        recipeGroupCreateObj.set("groupName", String.format("ISC%sPackageRecipes", dataSourceType));
        String groupId = (String) iris.classMethodObject("SDS.API.RecipeGroupAPI", "RecipeGroupCreate", recipeGroupCreateObj);

        for (String group : groups) {
            log.info(String.format("Creating %s recipe", group));
            IRISObject recipeCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.recipe.RecipeCreate", "%New");
            recipeCreateObj.set("name", group);
            recipeCreateObj.set("shortName", group);
            recipeCreateObj.set("groupId", Integer.parseInt(groupId));

            IRISObject recipeCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.recipe.RecipeCreateResponse", "%New");
            recipeCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "RecipeCreate", recipeCreateObj);

            recipeGuids.put(group, (String) recipeCreateRespObj.get("recipeGUID"));
            recipeIds.add((String) recipeCreateRespObj.get("id"));

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

                stagingActivityUpdateItemObj.set("id", tableIds.get(String.format("%s%s", schema, table)));

                List<String> fields = tableFields.get(String.format("%s%s", schema, table));
                if (fields == null) {
                    log.warn("null fields for table: " + table);
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
                updatePromotionActivityItemCreateObj.set("description", String.format("%s Update", table));
                updatePromotionActivityItemCreateObj.set("sqlExpression", updateSqlExpression);
                updatePromotionActivityItemCreateObj.set("activitySaveVersion", (i + 2)*2);
                updatePromotionActivityItemCreateObj.set("runOrder", (i+1)*10);

                IRISObject updatePromotionActivityItemCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.promotion.PromotionActivityItemCreateResponse", "%New");
                updatePromotionActivityItemCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "PromotionActivityItemCreate", promotionActivityCreateRespObj.get("id"), updatePromotionActivityItemCreateObj);

                IRISObject insertPromotionActivityItemCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.promotion.PromotionActivityItemCreate", "%New");
                insertPromotionActivityItemCreateObj.set("description", String.format("%s Insert", table));
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

    public boolean waitForSchedulableResourceTablePopulation() throws SQLException, InterruptedException {
        log.info("waitForSchedulableResourceTablePopulation()");

        boolean tableUpdated = false;
        long timeout = 10000;
        long startTime = System.currentTimeMillis();

        String query = "Select GUID From SDS_BusinessScheduler.SchedulableResource";
        PreparedStatement stmt = connection.prepareStatement(query);

        while (!tableUpdated && (System.currentTimeMillis() - startTime) < timeout) {

            ResultSet rs = stmt.executeQuery();

            List<String> guids = new ArrayList<>();
            while (rs.next()) {
                String guid = rs.getString("GUID");

                guids.add(guid);
            }

            tableUpdated = guids.containsAll(recipeGuids.values());
            Thread.sleep(500);
        }

        stmt.close();

        if (!tableUpdated) {
            log.error("Timeout reached before SchedulableResource table populated with new recipes. Cannot create Business Scheduler Tasks.");
        }

        return tableUpdated;
    }

    private void createScheduledTasks() {
        log.info("createScheduledTasks()");

        IRISObject scheduledTaskGroupCreateObj = (IRISObject) iris.classMethodObject("intersystems.businessScheduler.v1.scheduledTask.ScheduledTaskCreate", "%New");
        scheduledTaskGroupCreateObj.set("enabled", true);
        scheduledTaskGroupCreateObj.set("taskDescription", String.format("ISC%sPackageTaskGroup", dataSourceType));
        scheduledTaskGroupCreateObj.set("scheduledTaskType", "1");
        scheduledTaskGroupCreateObj.set("schedulingType", "1");

        IRISObject scheduledTaskGroupCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.businessScheduler.v1.scheduledTask.ScheduledTaskCreateResponse", "%New");
        scheduledTaskGroupCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.BusinessSchedulerAPI", "ScheduledTaskCreate", scheduledTaskGroupCreateObj);

        scheduledTaskGroupId = (String) scheduledTaskGroupCreateRespObj.get("id");

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

    private void exportBundle() {
        log.info("exportBundle()");

        IRISObject configExportList = (IRISObject) iris.classMethodObject("intersystems.ccm.v1.export.configExportList", "%New");
        IRISObject items = (IRISObject) iris.classMethodObject("%Library.ListOfObjects", "%New");

        tableGuids.forEach((table, guid) -> {
            IRISObject configExportListItem = (IRISObject) iris.classMethodObject("intersystems.ccm.v1.export.configExportListItem", "%New");
            configExportListItem.set("fileName", String.format("ISC%sPackageSource-%s.TotalViewDataSchemaDefinition", dataSourceType, table));
            configExportListItem.set("guid", guid);
            items.invoke("Insert", configExportListItem);
        });

        recipeGuids.forEach((recipe, guid) -> {
            IRISObject configExportListItem = (IRISObject) iris.classMethodObject("intersystems.ccm.v1.export.configExportListItem", "%New");
            configExportListItem.set("fileName", String.format("%s.TotalViewRecipe", recipe));
            configExportListItem.set("guid", guid);
            items.invoke("Insert", configExportListItem);
        });

        configExportList.set("items", items);

        IRISObject exportBundle = (IRISObject) iris.classMethodObject("intersystems.ccm.v1.export.Bundle", "%New");
        exportBundle = (IRISObject) iris.classMethodObject("SDS.API.CCMAPI", "ConfigExport", configExportList);
    }

    private void cleanup() {
        deleteScheduledTasks();
        deleteRecipes();
        deleteDataSchemaDefinitions();
        deleteDataSource();
    }

    private void deleteScheduledTasks() {
        log.info("deleteScheduledTasks()");

        log.info(String.format("Deleting scheduled task group with id %s", scheduledTaskGroupId));
        iris.classMethodVoid("SDS.API.BusinessSchedulerAPI", "ScheduledTaskDelete", Integer.parseInt(scheduledTaskGroupId));
    }

    private void deleteRecipes(){
        log.info("deleteRecipes()");
        for (String id : recipeIds) {
            log.info(String.format("Deleting recipe with id %s", id));
            iris.classMethodVoid("SDS.API.RecipesAPI", "DeleteRecipeActivitiesAndRecords", Integer.parseInt(id), true);
            IRISObject deleteRecipeResp = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.recipe.RecipeCleaningResponse", "%New");
            deleteRecipeResp = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "PermanentlyDeleteRecipe", Integer.parseInt(id), false);
        }
    }

    private void deleteDataSchemaDefinitions(){
        log.info("deleteDataSchemaDefinitions()");
        for (String id : tableIds.values()) {
            log.info(String.format("Deleting data schema definition with id %s", id));
            iris.classMethodVoid("SDS.API.DataCatalogAPI", "SchemaDefinitionDelete", Integer.parseInt(id));
        }
    }

    private void deleteDataSource(){
        log.info("deleteDataSource()");

        log.info(String.format("Deleting data source with id %s", dataSourceId));
        iris.classMethodVoid("SDS.API.DataSourceAPI", "DataSourceDelete", dataSourceId);
    }
}
