package com.intersystems.vendorautomation;

import java.net.HttpURLConnection;

import com.intersystems.jdbc.IRIS;
import com.intersystems.jdbc.IRISConnection;
import com.intersystems.jdbc.IRISDataSource;
import com.intersystems.jdbc.IRISObject;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    private String recipeGroupName;
    private Map<String, String> taskGuids = new HashMap<>();
    private String scheduledTaskGroupId;
    private String scheduledTaskGroupGuid;
    private String scheduledTaskGroupName;

    private IRISConnection connection;
    private IRIS iris;

    private int dataSourceId;
    private String baseName;
    private String dataSourceType;
    private String schema;

    private String scheduledTaskGroupIdToDelete;
    private String recipeGroupNameToDelete;
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
        runTypeHandlers.put("createTargetTables", () -> createTargetTables());
        runTypeHandlers.put("generateArtifacts", () -> generateArtifacts());
        runTypeHandlers.put("cleanupArtifacts", () -> cleanupArtifacts());

        String runType = config.getString("runType");

        ExceptionHandler handler = runTypeHandlers.get(runType);
        if (handler != null) {
            handler.handle();
        } else {
            throw new IllegalArgumentException("Invalid runType: " + runType);
        }
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

    private void generateArtifacts() throws Exception {
        log.info("generateArtifacts()");

        connectToIRIS();

        this.baseName = config.getString("generateArtifacts.name");
        this.dataSourceType = getDataSourceType(config.getInt("generateArtifacts.dataSourceId"));
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
        exportBundles();
        createArtifactIdFile();
    }

    private void cleanupArtifacts() throws Exception {
        log.info("cleanupArtifacts()");

        connectToIRIS();

        cleanup(config.getString("cleanupArtifacts.cleanupSourcePath"));
    }

    private void setConfig() {
        log.info("setConfig()");

        String configFilePath = System.getProperty("config.file");
        if (configFilePath != null) {
            config = ConfigFactory.parseFile(new File(configFilePath)).resolve();
        } else {
            config = ConfigFactory.load();
        }
    }

    private void connectToIRIS() throws Exception {
        log.info("connectToIRIS()");

        String server = config.getString("database.server");
        int port = config.getInt("database.port");
        String database = config.getString("database.database");
        String user = config.getString("database.user");

        log.info(String.format("Connecting to IRIS server at %s:%d, database=%s, user=%s", server, port, database, user));

        IRISDataSource dataSource = IrisDatabaseConnection.createDataSource(server, port, database, user, config.getString("database.password"));
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

    private int duplicateDataSource(int dataSourceId) throws Exception {
        log.info("duplicateDataSource()");

        if (dataSourceNameExists(baseName)) {
            throw new Exception(String.format("Data source with name %s already exists. Please specify a different name", baseName));
        }

        int newDataSourceId;
        IRISObject originalDataSource;

        try {
            originalDataSource = (IRISObject) iris.classMethodObject("SDS.DataLoader.DS.DataSource", "%OpenId", dataSourceId);
        }
        catch (Exception e) {
            throw new AppException(String.format("Data source with id %d does not exist", dataSourceId), e);
        }
        IRISObject newDataSource = (IRISObject) originalDataSource.invoke("%ConstructClone", 0);

        newDataSource.set("Name", baseName);

        Object sc;
        sc = newDataSource.invoke("%Save");

        if (sc == null) {
            throw new Exception("Could not create data source");
        }
        else if ((sc instanceof String) && (((String) sc).charAt(0) == '0')) {
            throw new Exception("Could not create data source:" + ((String) sc).substring(1));
        }

        String id = (String) newDataSource.invoke("%Id");
        if (id == null) {
            throw new Exception("Error retrieving new data source ID");
        }

        newDataSourceId = Integer.parseInt(id);

        log.info(String.format("New data source %s created with id=%s ", newDataSource.get("Name"), newDataSourceId));

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
        String serverString = config.hasPath("generateArtifacts.ui.port") ? String.format("%s:%d", server, config.getInt("generateArtifacts.ui.port")) : server;
        if (supportsSchema) {
            if (schema == "") {
                throw new Exception("Data source requires schema, but no schema specified.");
            }

            String encodedParamValue = URLEncoder.encode(schema, "UTF-8");
            urlString = String.format("http://%s/intersystems/data-loader/v1/dataSources/%s/schemas/members?schema=%s",
            serverString, dataSourceId, encodedParamValue);
        }
        else {
            if (schema != "") {
                throw new Exception(String.format("A schema %s was specified but the data source has no schemas.", schema));
            }

            urlString = String.format("http://%s/intersystems/data-loader/v1/dataSources/%s/schemas/members", serverString, dataSourceId);
        }

        URL url = new URL(urlString);
        log.info(String.format("Sending GET request to %s", url));

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        String userCredentials = String.format("%s:%s", config.getString("generateArtifacts.ui.user"), config.getString("generateArtifacts.ui.password"));
        String basicAuth = String.format("Basic %s", new String(Base64.getEncoder().encode(userCredentials.getBytes())));
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

    private void importDataSchemaDefinitions(JSONArray itemsArray) throws Exception {
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

            Object sc;
            sc = dataCatalogService.invoke("ProcessInput", importRequest);

            if (sc == null) {
                throw new Exception("Could not import data schema definitions.");
            }
            else if ((sc instanceof String) && (((String) sc).charAt(0) == '0')) {
                throw new Exception("Could not import data schema definitions:" + ((String) sc).substring(1));
            }

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
            String schemaName = config.getString("generateArtifacts.schema");
            tables.add((schemaName == "") ? table : table.substring(schemaName.length()));
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
            String groupName = baseName;
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

    private void createRecipes() throws Exception {
        log.info("createRecipes()");

        IRISObject recipeGroupCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipeGroup.v1.recipeGroupCreate", "%New");
        String groupName = String.format("%s Recipe Group", baseName);
        int suffix = 2;
        while (recipeNameExists(groupName) || recipeGroupNameExists(groupName)) {
            log.info(String.format("Recipe or RecipeGroup with name %s already exists. Trying %s %d", groupName, baseName, suffix));
            groupName = String.format("%s %d", baseName, suffix);
            suffix++;
        }
        recipeGroupCreateObj.set("groupName", groupName);
        this.recipeGroupName = groupName; // Store the recipe group name for cleanup
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
            String recipeName = group;
            suffix = 2;
            while (recipeNameExists(recipeName) || recipeGroupNameExists(recipeName)) {
                log.info(String.format("Recipe or RecipeGroup with name %s already exists. Trying %s %d", recipeName, group, suffix));
                recipeName = String.format("%s %d", group, suffix);
                suffix++;
            }
            recipeCreateObj.set("name", recipeName);
            recipeCreateObj.set("shortName", recipeName.length() > 15 ? recipeName.substring(0, 15) : recipeName);
            recipeCreateObj.set("groupId", Integer.parseInt(groupId));

            IRISObject recipeCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.recipe.RecipeCreateResponse", "%New");
            recipeCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "RecipeCreate", recipeCreateObj);

            recipeGuids.put(group, (String) recipeCreateRespObj.get("recipeGUID"));
            recipeIds.add((String) recipeCreateRespObj.get("id"));

            log.info("Creating staging activity");
            IRISObject stagingActivityCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityCreate", "%New");
            stagingActivityCreateObj.set("name", "StagingActivity");
            stagingActivityCreateObj.set("shortName", "SA");
            stagingActivityCreateObj.set("dataSourceId", dataSourceId);

            IRISObject stagingActivityCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityCreateResponse", "%New");
            stagingActivityCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "StagingActivityCreate", recipeCreateRespObj.get("id"), stagingActivityCreateObj);

            IRISObject stagingActivityUpdateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityUpdate", "%New");
            stagingActivityUpdateObj.set("name", stagingActivityCreateRespObj.get("name"));
            stagingActivityUpdateObj.set("saveVersion", 1);

            log.info("Creating promotion activity");
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
                   // log.warn("null fields for table: " + table);
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

                log.info(String.format("Creating promotion activity item %s", String.format("%s Update", table)));
                IRISObject updatePromotionActivityItemCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.promotion.PromotionActivityItemCreateResponse", "%New");
                updatePromotionActivityItemCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "PromotionActivityItemCreate", promotionActivityCreateRespObj.get("id"), updatePromotionActivityItemCreateObj);

                IRISObject insertPromotionActivityItemCreateObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.promotion.PromotionActivityItemCreate", "%New");
                insertPromotionActivityItemCreateObj.set("description", String.format("%s Insert", table));
                insertPromotionActivityItemCreateObj.set("sqlExpression", insertSqlExpression);
                insertPromotionActivityItemCreateObj.set("activitySaveVersion", (i + 3)*2);
                insertPromotionActivityItemCreateObj.set("runOrder", (i+1)*10 + 1);

                log.info(String.format("Creating promotion activity item %s", String.format("%s Insert", table)));
                IRISObject insertPromotionActivityItemCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.promotion.PromotionActivityItemCreateResponse", "%New");
                insertPromotionActivityItemCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "PromotionActivityItemCreate", promotionActivityCreateRespObj.get("id"), insertPromotionActivityItemCreateObj);
            }
            stagingActivityUpdateObj.set("dataSchemas", dataSchemas);

            log.info("Setting data schemas for staging activity");
            IRISObject stagingActivityUpdateRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivityUpdateResponse", "%New");
            stagingActivityUpdateRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "StagingActivityUpdate", stagingActivityCreateRespObj.get("id"), stagingActivityUpdateObj);

            /*
            IRISObject stagingActivity = (IRISObject) iris.classMethodObject("SDS.DataLoader.Staging.StagingActivity", "%OpenId", stagingActivityUpdateRespObj.get("id"));
            stagingActivity.set("Editing", false);
            Object sc;
            sc = stagingActivity.invoke("%Save");

            if (sc == null) {
                throw new Exception("Could not create staging activity");
            }
            else if ((sc instanceof String) && (((String) sc).charAt(0) == '0')) {
                throw new Exception("Could not create staging activity:" + ((String) sc).substring(1));
            }

            IRISObject stagingActivitySessionCloseRespObj = (IRISObject) iris.classMethodObject("intersystems.recipes.v1.activity.staging.StagingActivitySessionResponse", "%New");
            stagingActivitySessionCloseRespObj = (IRISObject) iris.classMethodObject("SDS.API.RecipesAPI", "StagingActivitySessionClose", stagingActivityUpdateRespObj.get("id"), true, false);
            */
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
        stmt.close();

        return id;
    }

    /*****************************************************************************
        Checking if SchedulableResource table is populated with new recipes
        Recipes must be available as SchedulableResources before creating Scheduled Tasks
     *****************************************************************************/

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

    private void createScheduledTasks() throws Exception {
        log.info("createScheduledTasks()");

        IRISObject scheduledTaskGroupCreateObj = (IRISObject) iris.classMethodObject("intersystems.businessScheduler.v1.scheduledTask.ScheduledTaskCreate", "%New");
        scheduledTaskGroupCreateObj.set("enabled", true);
        String scheduledTaskGroupName = baseName;
        int suffix = 2;
        while (scheduledTaskDescriptionExists(scheduledTaskGroupName)) {
            log.info(String.format("ScheduledTask with description %s already exists. Trying %s %d", scheduledTaskGroupName, baseName, suffix));
            scheduledTaskGroupName = String.format("%s %d", baseName, suffix);
            suffix++;
        }
        this.scheduledTaskGroupName = scheduledTaskGroupName;
        log.info(String.format("Creating ScheduledTaskGroup %s", scheduledTaskGroupName));
        scheduledTaskGroupCreateObj.set("taskDescription", scheduledTaskGroupName);
        scheduledTaskGroupCreateObj.set("scheduledTaskType", "1");
        scheduledTaskGroupCreateObj.set("schedulingType", "1");

        IRISObject scheduledTaskGroupCreateRespObj = (IRISObject) iris.classMethodObject("intersystems.businessScheduler.v1.scheduledTask.ScheduledTaskCreateResponse", "%New");
        scheduledTaskGroupCreateRespObj = (IRISObject) iris.classMethodObject("SDS.API.BusinessSchedulerAPI", "ScheduledTaskCreate", scheduledTaskGroupCreateObj);

        scheduledTaskGroupId = (String) scheduledTaskGroupCreateRespObj.get("id");
        scheduledTaskGroupGuid = (String) (String) ((IRISObject) scheduledTaskGroupCreateRespObj.get("schedulableResource")).get("guid");

        for (String group : groups) {
            log.info(String.format("Creating ScheduledTask for resource %s", recipeGuids.get(group)));
            IRISObject scheduledTaskCreateObj = (IRISObject) iris.classMethodObject("intersystems.businessScheduler.v1.scheduledTask.ScheduledTaskCreate", "%New");
            scheduledTaskCreateObj.set("enabled", true);
            scheduledTaskCreateObj.set("entityId", 1);
            scheduledTaskCreateObj.set("exceptionWorkflowRole", "System Administrator");
            scheduledTaskCreateObj.set("dependencyInactivityTimeout", 300);
            String scheduledTaskName = group;
            suffix = 2;
            while (scheduledTaskDescriptionExists(scheduledTaskName)) {
                log.info(String.format("ScheduledTask with description %s already exists. Trying %s %d", scheduledTaskName, group, suffix));
                scheduledTaskName = String.format("%s %d", group, suffix);
                suffix++;
            }
            scheduledTaskCreateObj.set("taskDescription", scheduledTaskName);
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
            String taskId = (String) scheduledTaskCreateRespObj.get("id");
            String taskGuid = getTaskGuid(taskId);
            taskGuids.put(group, taskGuid);
        }
    }

    /*****************************************************************************
        Methods to check if a specific name exists in the database.
        These methods query the database to determine if a data source, recipe, recipe group, or scheduled task
        with the given name already exists. They return a boolean indicating the presence of the name.
     *****************************************************************************/

     private boolean dataSourceNameExists(String name) throws SQLException {
        String query = "SELECT 1 FROM SDS_DataLoader_DS.DataSource WHERE Name = ? AND Deleted = 0";
        PreparedStatement stmt = connection.prepareStatement(query);
        stmt.setString(1, name);
        ResultSet rs = stmt.executeQuery();

        boolean exists = rs.next();
        stmt.close();

        return exists;
    }

    private boolean recipeNameExists(String name) throws SQLException {
        String query = "SELECT 1 FROM SDS_DataLoader.Recipe WHERE Name = ?";
        PreparedStatement stmt = connection.prepareStatement(query);
        stmt.setString(1, name);
        ResultSet rs = stmt.executeQuery();

        boolean exists = rs.next();
        stmt.close();

        return exists;
    }

    private boolean recipeGroupNameExists(String name) throws SQLException {
        String query = "SELECT 1 FROM SDS_DataLoader.RecipeGroup WHERE Name = ?";
        PreparedStatement stmt = connection.prepareStatement(query);
        stmt.setString(1, name);
        ResultSet rs = stmt.executeQuery();

        boolean exists = rs.next();
        stmt.close();

        return exists;
    }

    private boolean scheduledTaskDescriptionExists(String name) throws SQLException {
        log.info("scheduledTaskDescriptionExists()");
        String query = "SELECT 1 FROM SDS_BusinessScheduler.ScheduledTask WHERE TaskDescription = ? AND Deleted = 0";
        PreparedStatement stmt = connection.prepareStatement(query);
        stmt.setString(1, name);
        ResultSet rs = stmt.executeQuery();

        boolean exists = rs.next();
        stmt.close();
        return exists;
    }

    /*****************************************************************************
        Creating artifact files
        These files are necessary for a cleanup run
     *****************************************************************************/

    private void createArtifactIdFile() {
        Workbook workbook = new XSSFWorkbook();

        Sheet sheet = workbook.createSheet("Artifacts");

        Row row = sheet.createRow(0);

        row.createCell(0).setCellValue("Data Source Id");
        row.createCell(1).setCellValue("Scheduled Task Group Id");
        row.createCell(2).setCellValue("Recipe Ids");
        row.createCell(3).setCellValue("Data Schema Definition Ids");
        row.createCell(4).setCellValue("Recipe Group Name");

        row = sheet.createRow(1);

        Cell cell = row.createCell(0);
        cell.setCellValue(dataSourceId);
        cell = row.createCell(1);
        cell.setCellValue(scheduledTaskGroupId);
        cell = row.createCell(4);
        cell.setCellValue(recipeGroupName);

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

        for (int i = 0; i < 5; i++) {
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

    /*****************************************************************************
        CLEANUP METHODS
     *****************************************************************************/

    private void cleanup(String cleanupFile) throws Exception {
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
                        Cell recipeGroupCell = row.getCell(4);
                        if (recipeGroupCell != null) {
                            recipeGroupNameToDelete = recipeGroupCell.toString();
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
        deleteRecipeGroup();
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

    private void deleteRecipeGroup() throws SQLException {
        log.info("deleteRecipeGroup()");

        String recipeGroupName = recipeGroupNameToDelete;

        String query = "DELETE FROM SDS_DataLoader.RecipeGroup WHERE Name = ?";

        PreparedStatement stmt = connection.prepareStatement(query);
        stmt.setString(1, recipeGroupName);
        int rowsAffected = stmt.executeUpdate();
        log.info(String.format("Deleted %d recipe group(s) with name %s", rowsAffected, recipeGroupName));
        stmt.close();
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

    private void exportBundles() {
        log.info("exportBundle()");

        // Use CreateYAMLS to generate YAML files and create ZIP bundles
        try {
            // Create directories if they don't exist
            Files.createDirectories(Paths.get("schemadefs"));
            Files.createDirectories(Paths.get("recipes"));
            Files.createDirectories(Paths.get("scheduledtasksgroup"));
            Files.createDirectories(Paths.get("scheduledtasks"));

                        // Create CreateYAMLS instance and populate it with data
            CreateYAMLS createYAMLS = new CreateYAMLS();

            String entityGuid = getEntityGuid();
            String schedulableResourceGroupGuid = getSchedulableResourceGroupGuid();
            String workflowRoleGuid = getWorkflowRoleGuid();

                        // Set all required data from App class in a single call
            createYAMLS.setData(groups, tables, groupTableMapping, tableGuids, tableFields,
                               recipeGuids, taskGuids, iris, dataSourceId, dataSourceType,
                               recipeGroupName, baseName, entityGuid,
                               scheduledTaskGroupGuid, schedulableResourceGroupGuid,
                               config.getString("database.user"), workflowRoleGuid);

            // Generate YAML files
            createYAMLS.createDataSchemaDefinitionYAMLs();
            createYAMLS.createRecipeYAMLs();
            createYAMLS.createScheduledTasksGroupYAML();
            createYAMLS.createScheduledTaskYAMLs();

            // Create ZIP files from the generated YAML files
            File schemaZip = createZipFromDirectory("schemadefs", "Schema");
            File recipeZip = createZipFromDirectory("recipes", "Recipe");
            File taskGroupZip = createZipFromDirectory("scheduledtasksgroup", "ScheduledTaskGroup");
            File taskZip = createZipFromDirectory("scheduledtasks", "ScheduledTask");

            log.info("All bundles exported and downloaded using CreateYAMLS.");
        } catch (Exception e) {
            log.error("Failed to export and download bundle zips using CreateYAMLS", e);
        }
    }

    private File createZipFromDirectory(String directoryPath, String label) throws IOException {
        Path outputDir = Paths.get("exports");
        Files.createDirectories(outputDir);

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        Path zipPath = outputDir.resolve(label + "_Bundle_" + timestamp + ".zip");

        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            Path directory = Paths.get(directoryPath);
            if (Files.exists(directory)) {
                Files.walk(directory)
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        try {
                            String fileName = path.getFileName().toString();
                            zipOut.putNextEntry(new ZipEntry(fileName));
                            Files.copy(path, zipOut);
                            zipOut.closeEntry();
                            log.info("Added to ZIP: " + fileName);
                        } catch (IOException e) {
                            log.error("Error adding file to ZIP: " + path, e);
                        }
                    });
            }
        }

        log.info("ZIP created: " + zipPath.toAbsolutePath());
        return zipPath.toFile();
    }

    private String getEntityGuid() throws SQLException {
        log.info("getEntityGuid()");

        String query = "SELECT InternalGUID FROM SDS.EntityMaster WHERE ID = 1";


        PreparedStatement stmt = connection.prepareStatement(query);
        ResultSet rs = stmt.executeQuery();

        rs.next();
        String guid = rs.getString("InternalGUID");
        stmt.close();

        return guid;
    }

    private String getSchedulableResourceGroupGuid() throws SQLException {
        log.info("getSchedulableResourceGroupGuid()");

        String query = "SELECT GUID FROM SDS_BusinessScheduler.SchedulableResource WHERE Name = ? AND Type = 'Group'";

        PreparedStatement stmt = connection.prepareStatement(query);
        stmt.setString(1, scheduledTaskGroupName);
        ResultSet rs = stmt.executeQuery();

        rs.next();
        String guid = rs.getString("GUID");
        stmt.close();

        return guid;
    }

    private String getTaskGuid(String taskId) throws SQLException {
        log.info("getTaskGuid()");

        String query = "SELECT InternalGUID FROM SDS_BusinessScheduler.ScheduledTask WHERE ID = ?";

        PreparedStatement stmt = connection.prepareStatement(query);
        stmt.setString(1, taskId);
        ResultSet rs = stmt.executeQuery();

        rs.next();
        String guid = rs.getString("InternalGUID");
        stmt.close();

        return guid;
    }

    private String getWorkflowRoleGuid() throws SQLException {
        log.info("getWorkflowRoleGuid()");

        String query = "SELECT InternalGUID FROM B360_Security.UserRole WHERE ID = 1";

        PreparedStatement stmt = connection.prepareStatement(query);
        ResultSet rs = stmt.executeQuery();

        rs.next();
        String guid = rs.getString("InternalGUID");
        stmt.close();

        return guid;
    }
}
