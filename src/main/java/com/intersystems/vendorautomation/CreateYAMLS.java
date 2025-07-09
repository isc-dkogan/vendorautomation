package com.intersystems.vendorautomation;

import java.io.FileWriter;
import java.io.IOException;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.Yaml;

import com.intersystems.jdbc.IRISObject;

import com.intersystems.jdbc.IRIS;

import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CreateYAMLS {
    private Set<String> groups;
    private Set<String> tables;
    private Map<String, List<String>> groupTableMapping;
    private Map<String, String> tableGuids = new HashMap<>();
    private Map<String, List<String>> tableFields = new HashMap<>();
    private Map<String, String> recipeGuids = new HashMap<>();
    private Map<String, String> taskGuids = new HashMap<>();

    private IRIS iris;

    private int dataSourceId;
    private String dataSourceType;
    private String recipeGroupName;
    private String baseName;
    private String entityGuid;
    private String scheduledTaskGroupGuid;
    private String schedulableResourceGroupGuid;
    private String user;
    private String workflowRoleGuid;

    // Single method to set all required data
    public void setData(Set<String> groups, Set<String> tables, Map<String, List<String>> groupTableMapping,
                       Map<String, String> tableGuids, Map<String, List<String>> tableFields,
                       Map<String, String> recipeGuids, Map<String, String> taskGuids,
                       IRIS iris, int dataSourceId, String dataSourceType,
                       String recipeGroupName, String baseName, String entityGuid,
                       String scheduledTaskGroupGuid, String schedulableResourceGroupGuid,
                       String user, String workflowRoleGuid) {

        this.groups = groups;
        this.tables = tables;
        this.groupTableMapping = groupTableMapping;
        this.tableGuids = tableGuids;
        this.tableFields = tableFields;
        this.recipeGuids = recipeGuids;
        this.taskGuids = taskGuids;
        this.iris = iris;
        this.dataSourceId = dataSourceId;
        this.dataSourceType = dataSourceType;
        this.recipeGroupName = recipeGroupName;
        this.baseName = baseName;
        this.entityGuid = entityGuid;
        this.scheduledTaskGroupGuid = scheduledTaskGroupGuid;
        this.schedulableResourceGroupGuid = schedulableResourceGroupGuid;
        this.user = user;
        this.workflowRoleGuid = workflowRoleGuid;
    }

    private static final Logger log = LogManager.getLogger(App.class);

    // Helper method to handle null values while preserving original types
    private Object getValueOrEmpty(Object value) {
        return value != null ? value : "";
    }

    public void createDataSchemaDefinitionYAMLs() throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(FlowStyle.BLOCK);

        Yaml yaml = new Yaml(options);

        for (String table : tableGuids.keySet()) {

            String tableGuid = tableGuids.get(table);
            if (tableGuid == null || tableGuid.isEmpty()) {
                log.warn("Skipping table '" + table + "' - no GUID found");
                continue;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("apiVersion", "v1");
            data.put("kind", "TotalViewDataSchemaDefinition");

            // Metadata map with GUID for this specific table
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("guid", tableGuid);
            metadata.put("version", 1);
            data.put("metadata", metadata);

            // Spec map - using LinkedHashMap to guarantee insertion order
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("name", baseName);
            spec.put("dataSourceItemName", table);
            spec.put("extractionStrategy", "Simple Load");
            spec.put("extractionStrategyField", "");
            spec.put("ObjectName", table);
            spec.put("catalogDescription", "");
            List<String> primaryKeyFieldList = new ArrayList<>();
            spec.put("primaryKeyFieldList", primaryKeyFieldList);
            spec.put("entityName", table);
            spec.put("dataSource", baseName);

            // Generate fields data
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

                Map<String, Object> field = new LinkedHashMap<>();
                field.put("fieldName", getValueOrEmpty(column.get("FieldName")));
                field.put("fieldType", getValueOrEmpty(column.get("FieldType")));
                field.put("minVal", getValueOrEmpty(column.get("Minval")));
                field.put("maxVal", getValueOrEmpty(column.get("Maxval")));
                field.put("scale", getValueOrEmpty(column.get("Scale")));
                field.put("required", getValueOrEmpty(column.get("Required")));
                field.put("length", getValueOrEmpty(column.get("Length")));
                field.put("defaultValue", getValueOrEmpty(column.get("DefaultValue")));
                field.put("dataFormat", getValueOrEmpty(column.get("DataFormat")));
                field.put("srcDataType", getValueOrEmpty(column.get("SrcDataType")));
                field.put("fieldDescription", getValueOrEmpty(column.get("FieldDescription")));

                fields.add(field);
            }

            // Add fields last to the spec
            spec.put("fields", fields);
            data.put("spec", spec);

            try (FileWriter writer = new FileWriter(String.format("schemadefs/%s.TotalViewDataSchemaDefinition", table))) {
                yaml.dump(data, writer);
                log.info(String.format("Created schemadefs/%s.TotalViewDataSchemaDefinition", table));
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

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("apiVersion", "v1");
            data.put("kind", "TotalViewRecipe");

            Map<String, Object> metadata = new LinkedHashMap<>();
            String recipeGuid = recipeGuids.get(group);
            metadata.put("guid", recipeGuid);
            metadata.put("version", 1);

            data.put("metadata", metadata);

            // Spec map - using LinkedHashMap to guarantee insertion order
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("name", group);
            spec.put("shortName", group);
            spec.put("recordStepModeActive", "");
            spec.put("maxRows", "0");
            spec.put("recipeActiveStatus", "Active");
            spec.put("groupName", recipeGroupName);

            List<Map<String, Object>> stagingActivities = new ArrayList<>();
            Map<String, Object> stagingActivity = new HashMap<>();
            stagingActivity.put("dataSourceName", baseName);
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
                   // log.info("null fields for table: " + table);
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
                promotionUpdateItem.put("description", String.format("%s Update", table));
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
                promotionInsertItem.put("description", String.format("%s Insert", table));
                promotionInsertItem.put("runOrder", (i+1)*10 + 1);

                promotionItems.add(promotionUpdateItem);
                promotionItems.add(promotionInsertItem);
            }

            stagingActivity.put("items", stagingItems);
            stagingActivities.add(stagingActivity);
            promotionActivity.put("items", promotionItems);
            promotionActivities.add(promotionActivity);

            // Add stagingActivities second to last and promotionActivities last
            spec.put("stagingActivities", stagingActivities);
            spec.put("promotionActivities", promotionActivities);

            data.put("spec", spec);

            try (FileWriter writer = new FileWriter(String.format("recipes/%s.TotalViewRecipe", group))) {
                yaml.dump(data, writer);
            }

            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void createScheduledTasksGroupYAML() throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(FlowStyle.BLOCK);

        Yaml yaml = new Yaml(options);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("apiVersion", "v1");
        data.put("kind", "TotalViewScheduledTask");

        // Metadata map with GUID for this specific task
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("guid", scheduledTaskGroupGuid);
        metadata.put("version", 1);
        data.put("metadata", metadata);

        // Spec map - using LinkedHashMap to guarantee insertion order
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("owner", user);
        spec.put("taskDescription", baseName);
        spec.put("schedulingType", "Manually Run");
        spec.put("enabled", 1);
        spec.put("scheduledTaskType", "SimpleGroup");
        Map<String, Object> schedulableResource = new LinkedHashMap<>();
        schedulableResource.put("type", "Group");
        schedulableResource.put("identifier", schedulableResourceGroupGuid);
        spec.put("schedulableResource", schedulableResource);

        data.put("spec", spec);

        try (FileWriter writer = new FileWriter(String.format("scheduledtasksgroup/%s.TotalViewScheduledTask", baseName))) {
            yaml.dump(data, writer);
            log.info(String.format("Created scheduledtasksgroup/%s.TotalViewScheduledTask", baseName));
        }

        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void createScheduledTaskYAMLs() throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(FlowStyle.BLOCK);

        for (String group : groups) {
            Yaml yaml = new Yaml(options);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("apiVersion", "v1");
            data.put("kind", "TotalViewScheduledTask");

            // Metadata map with GUID for this specific task
            Map<String, Object> metadata = new LinkedHashMap<>();
            String taskGuid = taskGuids.get(group);
            metadata.put("guid", taskGuid);
            metadata.put("version", 1);
            data.put("metadata", metadata);

            // Spec map - using LinkedHashMap to guarantee insertion order
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("owner", user);
            spec.put("taskDescription", group);
            spec.put("schedulingType", "Manually Run");
            spec.put("enabled", 1);
            spec.put("scheduledTaskType", "Task");
            Map<String, Object> schedulableResource = new LinkedHashMap<>();
            schedulableResource.put("type", "Recipe");
            schedulableResource.put("identifier", recipeGuids.get(group));
            spec.put("schedulableResource", schedulableResource);
            spec.put("entity", entityGuid);
            spec.put("exceptionWorkflowRole", workflowRoleGuid);
            spec.put("dependencyInactivityTimeout", "");
            spec.put("schedulingGroup", scheduledTaskGroupGuid);
            Map<String, Object> schedulingProperties = new LinkedHashMap<>();
            schedulingProperties.put("dependencyInactivityTimeout", 300);
            spec.put("schedulingProperties", schedulingProperties);

            data.put("spec", spec);

            try (FileWriter writer = new FileWriter(String.format("scheduledtasks/%s.TotalViewScheduledTask", group))) {
                yaml.dump(data, writer);
                log.info(String.format("Created scheduledtasks/%s.TotalViewScheduledTask", group));
            }

            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
