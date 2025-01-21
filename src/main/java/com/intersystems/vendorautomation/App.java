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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigResolveOptions;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;


public class App {

    private Config config;

    private Set<String> groups = new HashSet<>();
    private Set<String> tables = new HashSet<>();
    private Map<String, List<String>> groupTableMapping = new HashMap<>();
    private Map<String, String> tableIds = new HashMap<>();
    private Map<String, String> tableGuids = new HashMap<>();
    private Map<String, List<String>> tableFields = new HashMap<>();
    private Map<String, String> recipeGuids = new HashMap<>();
    private List<String> recipeIds = new ArrayList<>();
    private String scheduledTaskGroupId;

    private IRISConnection connection;
    private IRIS iris;

    private int dataSourceId;
    private String dataSourceType;
    private String schema;

    private String scheduledTaskGroupIdToDelete;
    private List<String> recipeIdsToDelete = new ArrayList<>();
    private List<String> tableIdsToDelete = new ArrayList<>();

    private static final Logger log = LogManager.getLogger(App.class);
    public static void main(String[] args) throws Exception {
        App app = new App();

        try {
            app.run();
        }
        catch (Exception e) {
            log.error("Run failed, ", e);
        }
    }

    @FunctionalInterface
    public interface ExceptionHandler {
        void handle() throws Exception;
    }

    private void run() throws Exception {
        setConfig();

        Map<String, ExceptionHandler> runTypeHandlers = new HashMap<>();
        runTypeHandlers.put("generateArtifacts", () -> generateArtifacts());
        runTypeHandlers.put("cleanupArtifacts", () -> cleanupArtifacts());
        runTypeHandlers.put("createTargetTables", () -> createTargetTables());

        String runType = config.getString("runType");

        ExceptionHandler handler = runTypeHandlers.get(runType);
        if (handler != null) {
            handler.handle();
        } else {
            throw new IllegalArgumentException("Invalid runType: " + runType);
        }
    }

    private void generateArtifacts() throws Exception {
        log.info("generateArtifacts()");

        connectToIRIS();

        this.dataSourceId = duplicateDataSource(config.getInt("generateArtifacts.dataSourceId"));
        this.schema = config.getString("generateArtifacts.schema");

        JSONArray dataSourceItems = getDataSourceItems();
        importDataSchemaDefinitions(dataSourceItems);
        publishDataSchemaDefinitions();
        setDataSchemaDefinitionInformation();
        setMapping();
        createRecipes();
        boolean tablePopulated = waitForSchedulableResourceTablePopulation();
        if (tablePopulated) {
            createScheduledTasks();
        }

        createArtifactIdFile();
    }

    private void cleanupArtifacts() throws Exception {
        log.info("cleanupArtifacts()");

        connectToIRIS();

        cleanup(config.getString("cleanupArtifacts.cleanupSourcePath"));
    }

    private void createTargetTables() throws Exception {
        log.info("createTargetTables()");

        String xmlSourcePath = config.getString("createTargetTables.xmlSourcePath");
        File file = new File(xmlSourcePath);

        if (file.exists()) {
            dataSourceType = getDataSourceType(config.getInt("generateArtifacts.dataSourceId"));

            setMapping();

            XMLProcessor xmlProcessor = new XMLProcessor(dataSourceType, xmlSourcePath);
            List<String> tableKeywords = groups.stream()
                                               .map(element -> String.format("Staging.%s", element))
                                               .collect(Collectors.toList());
            xmlProcessor.process(tableKeywords);
        }
    }

    private void setConfig() {
        log.info("setConfig()");

        config = ConfigFactory.load();
    }

    private void connectToIRIS() throws Exception {
        log.info("connectToIRIS()");

        IRISDataSource dataSource = IrisDatabaseConnection.createDataSource(config.getString("database.server"),
                                                                            config.getInt("database.port"),
                                                                            config.getString("database.database"),
                                                                            config.getString("database.user"),
                                                                            config.getString("database.password"));
        connection = (IRISConnection) dataSource.getConnection();
        iris = IRIS.createIRIS(connection);
    }

    private String getDataSourceType(int dataSourceId) {
        try {
            IRISObject originalDataSource;
            originalDataSource = (IRISObject) iris.classMethodObject("SDS.DataLoader.DS.DataSource", "%OpenId", dataSourceId);

            return ((String) originalDataSource.invoke("%GetParameter", "DATASOURCENAME")).split(" ")[0];
        }
        catch (Exception e) {
            throw new AppException(String.format("Data source with id %d does not exist", dataSourceId), e);
        }

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
        newDataSource.set("Name", String.format("ISC %s Package Source", dataSourceType));

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
        String server = config.getString("database.server");
        supportsSchema = (capabilitiesCode.intValue() & supportsSchemaVal.intValue()) != 0;

        String urlString;
        if (supportsSchema) {
            if (schema == "") {
                throw new Exception("Data source requires schema, but no schema specified.");
            }

            String encodedParamValue = URLEncoder.encode(schema, "UTF-8");
            urlString = String.format("http://%s/intersystems/data-loader/v1/dataSources/%s/schemas/members?schema=%s",
            server, dataSourceId, encodedParamValue);
        }
        else {
            if (schema != "") {
                throw new Exception(String.format("A schema %s was specified but the data source has no schemas.", schema));
            }

            urlString = String.format("http://%s/intersystems/data-loader/v1/dataSources/%s/schemas/members", server, dataSourceId);
        }

        URL url = new URL(urlString);
        log.info(String.format("Sending GET request to %s", url));

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        String userCredentials = String.format("%s:%s", config.getString("generateArtifacts.ui.user"), config.getString("generateArtifacts.ui.password"));
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
            tables.add(table);
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

    private void setMapping() {
        log.info("setMapping()");

        String mappingSourcePath = config.getString("generateArtifacts.mappingSourcePath");
        if (mappingSourcePath == "") {
            String groupName = String.format("ISC %s Package Recipe", dataSourceType);
            groups.add(groupName);
            groupTableMapping.put(groupName, new ArrayList<>(tables));
        }
        else {
            ExcelReader excelReader = new ExcelReader(mappingSourcePath);
            groups = excelReader.getUniqueGroupNames();
            tables = excelReader.getAllTableNames();
            groupTableMapping = excelReader.getGroupTableMap();
        }
    }

    private void createRecipes() throws SQLException {
        log.info("createRecipes()");

        IRISObject recipeGroupCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipeGroup.v1.recipeGroupCreate", "%New");
        String groupName = String.format("ISC %s Package", dataSourceType);
        recipeGroupCreateObj.set("groupName", groupName);
        String groupId;
        try {
            groupId = (String) iris.classMethodObject("SDS.API.RecipeGroupAPI", "RecipeGroupCreate", recipeGroupCreateObj);
        }
        catch (Exception e) {
            groupId = getRecipeGroupId(groupName);
        }

        for (String group : groups) {
            log.info(String.format("Creating %s recipe", group));
            IRISObject recipeCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.recipe.RecipeCreate", "%New");
            recipeCreateObj.set("name", group);
            recipeCreateObj.set("shortName", group.length() > 15 ? group.substring(0, 15) : group);
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

    private String getRecipeGroupId(String recipeGroupName) throws SQLException {
        log.info("getRecipeGroupId()");

        String query = "SELECT ID FROM SDS_DataLoader.RecipeGroup WHERE Name = ?";


        PreparedStatement stmt = connection.prepareStatement(query);
        stmt.setString(1, recipeGroupName);
        ResultSet rs = stmt.executeQuery();

        rs.next();
        String id = rs.getString("ID");

        return id;
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
        scheduledTaskGroupCreateObj.set("taskDescription", String.format("ISC %s Package", dataSourceType));
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

    private void createArtifactIdFile() {
        Workbook workbook = new XSSFWorkbook();

        Sheet sheet = workbook.createSheet("Artifacts");

        Row row = sheet.createRow(0);

        row.createCell(0).setCellValue("Data Source Id");
        row.createCell(1).setCellValue("Scheduled Task Group Id");
        row.createCell(2).setCellValue("Recipe Ids");
        row.createCell(3).setCellValue("Data Schema Definition Ids");

        row = sheet.createRow(1);

        Cell cell = row.createCell(0);
        cell.setCellValue(dataSourceId);
        cell = row.createCell(1);
        cell.setCellValue(scheduledTaskGroupId);

        for (int i = 0; i < recipeIds.size(); i++) {
            row = sheet.getRow(i + 1);
            if (row == null) {
                row = sheet.createRow(i + 1);
            }
            cell = row.createCell(2);
            cell.setCellValue(recipeIds.get(i));
        }

        Iterator<String> tableIdIterator = tableIds.values().iterator();

        int rowIndex = 1;

        while (tableIdIterator.hasNext()) {
            row = sheet.getRow(rowIndex);
            if (row == null) {
                row = sheet.createRow(rowIndex);
            }

            cell = row.createCell(3);
            cell.setCellValue(tableIdIterator.next());

            rowIndex++;
        }

        for (int i = 0; i < 3; i++) {
            sheet.autoSizeColumn(i);
        }

        String datetime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        try (FileOutputStream fileOut = new FileOutputStream(String.format("%s/%s.xlsx", config.getString("generateArtifacts.artifactIdsFolder"), datetime))) {
            workbook.write(fileOut);
            log.info("Excel file created successfully!");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                workbook.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // private void exportBundle() {
    //     log.info("exportBundle()");

    //     IRISObject configExportList = (IRISObject) iris.classMethodObject("intersystems.ccm.v1.export.configExportList", "%New");
    //     IRISObject items = (IRISObject) iris.classMethodObject("%Library.ListOfObjects", "%New");

    //     tableGuids.forEach((table, guid) -> {
    //         IRISObject configExportListItem = (IRISObject) iris.classMethodObject("intersystems.ccm.v1.export.configExportListItem", "%New");
    //         configExportListItem.set("fileName", String.format("ISC%sPackageSource-%s.TotalViewDataSchemaDefinition", dataSourceType, table));
    //         configExportListItem.set("guid", guid);
    //         items.invoke("Insert", configExportListItem);
    //     });

    //     recipeGuids.forEach((recipe, guid) -> {
    //         IRISObject configExportListItem = (IRISObject) iris.classMethodObject("intersystems.ccm.v1.export.configExportListItem", "%New");
    //         configExportListItem.set("fileName", String.format("%s.TotalViewRecipe", recipe));
    //         configExportListItem.set("guid", guid);
    //         items.invoke("Insert", configExportListItem);
    //     });

    //     configExportList.set("items", items);

    //     IRISObject exportBundle = (IRISObject) iris.classMethodObject("intersystems.ccm.v1.export.Bundle", "%New");
    //     exportBundle = (IRISObject) iris.classMethodObject("SDS.API.CCMAPI", "ConfigExport", configExportList);
    // }

    private void cleanup(String cleanupFile) {
        log.info("cleanup()");

        try (FileInputStream fis = new FileInputStream(new File(cleanupFile));
            Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    if (i == 1) {
                        Cell dataSourceCell = row.getCell(0);
                        if (dataSourceCell != null) {
                            dataSourceId = (int) Double.parseDouble(dataSourceCell.toString());
                        }
                        Cell taskGroupCell = row.getCell(1);
                        if (taskGroupCell != null) {
                            scheduledTaskGroupIdToDelete = taskGroupCell.toString();
                        }
                    }
                    Cell recipeCell = row.getCell(2);
                    if (recipeCell != null) {
                        recipeIdsToDelete.add(recipeCell.toString());
                    }
                    Cell tableCell = row.getCell(3);
                    if (tableCell != null) {
                        tableIdsToDelete.add(tableCell.toString());
                    }
                }
            }
        }
        catch (IOException e) {
            log.error("Error reading Excel file: " + e.getMessage());
        }

        deleteScheduledTasks();
        deleteRecipes();
        deleteDataSchemaDefinitions();
        deleteDataSource();
    }

    private void deleteScheduledTasks() {
        log.info("deleteScheduledTasks()");

        log.info(String.format("Deleting scheduled task group with id %s", scheduledTaskGroupIdToDelete));
        iris.classMethodVoid("SDS.API.BusinessSchedulerAPI", "ScheduledTaskDelete", Integer.parseInt(scheduledTaskGroupIdToDelete));
    }

    private void deleteRecipes(){
        log.info("deleteRecipes()");
        for (String id : recipeIdsToDelete) {
            log.info(String.format("Deleting recipe with id %s", id));
            iris.classMethodVoid("SDS.API.RecipesAPI", "DeleteRecipeActivitiesAndRecords", Integer.parseInt(id), true);
            IRISObject deleteRecipeResp = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.recipe.RecipeCleaningResponse", "%New");
            deleteRecipeResp = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "PermanentlyDeleteRecipe", Integer.parseInt(id), false);
        }
    }

    private void deleteDataSchemaDefinitions(){
        log.info("deleteDataSchemaDefinitions()");
        for (String id : tableIdsToDelete) {
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
