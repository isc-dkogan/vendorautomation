package com.intersystems.vendorautomation;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.File;
import java.util.List;

public class XMLProcessor {

    public XMLProcessor(String filePath, List<String> stagingTableNames) {
        removeIrrelevantClasses(filePath, stagingTableNames);
        removeTags(filePath);
    }

    private void removeIrrelevantClasses(String filePath, List<String> stagingTableNames) {
        try {
            // String filePath = "allclasses.xml";
            String classTagName = "Class";
            String nameAttribute = "name";

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            Document document = builder.parse(new File(filePath));
            document.getDocumentElement().normalize();

            NodeList classList = document.getElementsByTagName(classTagName);

            for (int i = 0; i < classList.getLength(); i++) {
                Node node = classList.item(i);

                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element classElement = (Element) node;

                    String attrValue = classElement.getAttribute(nameAttribute).trim();
                    System.out.println("Processing Class with name attribute: " + attrValue);

                    boolean stagingTableClass = stagingTableNames.stream().anyMatch(attrValue::contains);

                    if (!stagingTableClass) {
                        System.out.println("Removing Class: " + attrValue);
                        classElement.getParentNode().removeChild(classElement);
                        i--;
                    }
                }
            }

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            DOMSource source = new DOMSource(document);
            StreamResult result = new StreamResult(new File(filePath));
            transformer.transform(source, result);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void removeTags(String filePath) {
        try {
            String[] tagsToRemove = {"Storage", "SqlColumnNumber", "TimeChanged", "TimeCreated"};

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            Document document = builder.parse(new File(filePath));
            document.getDocumentElement().normalize();  // Normalize the XML structure

            for (String tagName : tagsToRemove) {
                NodeList nodeList = document.getElementsByTagName(tagName);

                for (int i = nodeList.getLength() - 1; i >= 0; i--) {
                    Node node = nodeList.item(i);

                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        System.out.println("Removing " + tagName + " element and its children.");
                        node.getParentNode().removeChild(node);
                    }
                }
            }

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            DOMSource source = new DOMSource(document);
            StreamResult result = new StreamResult(new File(filePath));
            transformer.transform(source, result);

            System.out.println("All SqlColumnNumber and Storage tags with their nested elements were removed successfully.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}