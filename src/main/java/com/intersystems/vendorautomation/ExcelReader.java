package com.intersystems.vendorautomation;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

public class ExcelReader {

    private Map<String, List<String>> groupTableMap = new HashMap<>();

    public ExcelReader(String excelFilePath) {
        loadDataFromExcel(excelFilePath);
    }

    private void loadDataFromExcel(String excelFilePath) {
        try (FileInputStream fis = new FileInputStream(excelFilePath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);

            for (Row row : sheet) {

                Cell groupCell = row.getCell(0);
                Cell tableCell = row.getCell(1);

                String group = groupCell.getStringCellValue();
                String table = tableCell.getStringCellValue();

                groupTableMap.computeIfAbsent(group, k -> new ArrayList<>()).add(table);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Set<String> getUniqueGroupNames() {
        return groupTableMap.keySet();
    }

    public Map<String, List<String>> getGroupTableMap() {
        return groupTableMap;
    }
}

