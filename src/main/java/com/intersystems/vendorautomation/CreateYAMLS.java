package com.intersystems.vendorautomation;

import java.io.FileWriter;
import java.io.IOException;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.Yaml;

import com.intersystems.jdbc.IRISObject;
import java.net.HttpURLConnection;

import com.intersystems.jdbc.IRIS;
import com.intersystems.jdbc.IRISConnection;
import com.intersystems.jdbc.IRISDataSource;

import java.io.*;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

public class CreateYAMLS {
    private Set<String> groups;
    private Set<String> tables;
    private Map<String, List<String>> groupTableMapping;
    private Map<String, String> tableGuids = new HashMap<>();
    private Map<String, List<String>> tableFields = new HashMap<>();

    private IRIS iris;

    private int dataSourceId;
    private String dataSourceType;

    private static final Logger log = LogManager.getLogger(App.class);

    private void createDataSchemaDefinitionYAMLs() throws IOException {
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
            spec.put("name", String.format("ISC%sPackage-%s", dataSourceType, table));
            spec.put("dataSourceItemName", table);
            spec.put("extractionStrategy", "Simple Load");
            spec.put("extractionStrategyField", "");
            spec.put("ObjectName", table);
            spec.put("catalogDescription", "");
            List<String> primaryKeyFieldList = new ArrayList<>();
            spec.put("primaryKeyFieldList", primaryKeyFieldList);
            spec.put("entityName", table);
            spec.put("dataSource", String.format("ISC%sPackage", dataSourceType));

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

            try (FileWriter writer = new FileWriter(String.format("schemadefs/%s.TotalViewDataSchemaDefinition", table))) {
                yaml.dump(data, writer);
                log.info(String.format("Created file #%d schemadefs/%s.TotalViewDataSchemaDefinition", tableNum, table));
                tableNum ++;
            }

            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void createRecipeYAMLs() throws IOException {
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
            spec.put("groupName", String.format("ISC%sPackage", dataSourceType));

            List<Map<String, Object>> stagingActivities = new ArrayList<>();
            Map<String, Object> stagingActivity = new HashMap<>();
            stagingActivity.put("dataSourceName", String.format("ISC%sPackageSource", dataSourceType));
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
                    log.info("null fields for table: " + table);
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

    private void createScheduledTaskYAMLs() throws IOException {
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
            spec.put("businessEventTag", String.format("ISC%sPackageTaskGroup", dataSourceType));
            spec.put("dependencyInactivityTimeout", "");
            spec.put("schedulingGroup", "");
            Map<String, Object> schedulingProperties = new HashMap<>();
            schedulingProperties.put("dependencyInactivityTimeout", 300);
            spec.put("schedulingProperties", schedulingProperties);

            try (FileWriter writer = new FileWriter(String.format("scheduledtasks/%s.TotalViewScheduledTask", group))) {
                yaml.dump(data, writer);
            }
        }
    }
}
