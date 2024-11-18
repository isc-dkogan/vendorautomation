package com.intersystems.vendorautomation;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class XMLProcessor {

    private String dataSourceType;
    private String filePath;
    private String newFilePath = "targetclasses.xml";

    private static final Logger log = LogManager.getLogger(App.class);
    public XMLProcessor(String dataSourceType, String filePath) {
        this.dataSourceType = dataSourceType;
        this.filePath = filePath;
    }

    public void process(List<String> stagingTablePatterns) throws ParserConfigurationException, SAXException, IOException, TransformerConfigurationException, TransformerException {
        removeIrrelevantClasses(stagingTablePatterns);
        updateClassNames();
        removeTags();
        updateSuperTags();
        addBitemporalColumnsClass();
    }

    private void removeIrrelevantClasses(List<String> stagingTablePatterns) throws ParserConfigurationException, SAXException, IOException, TransformerConfigurationException, TransformerException {
        log.info("removeIrrelevantClasses()");

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

                boolean stagingTableClass = stagingTablePatterns.stream().anyMatch(attrValue::contains);

                if (!stagingTableClass) {
                    classElement.getParentNode().removeChild(classElement);
                    i--;
                }
            }
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(document);
        StreamResult result = new StreamResult(new File(newFilePath));
        transformer.transform(source, result);
    }

    private void updateClassNames() throws ParserConfigurationException, SAXException, IOException, TransformerConfigurationException, TransformerException {
        log.info("updateClassNames()");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        Document document = builder.parse(new File(newFilePath));
        document.getDocumentElement().normalize();  // Normalize the XML structure

        NodeList classList = document.getElementsByTagName("Class");

        for (int i = 0; i < classList.getLength(); i++) {
            Element classElement = (Element) classList.item(i);

            String originalName = classElement.getAttribute("name").trim();

            String[] parts = originalName.split("\\.");
            if (parts.length >= 5 && parts[0].equals("Staging") && parts[2].equals("sa") && parts[3].equals("v1")) {
                String recipe = parts[1];
                String table = parts[4];

                String newName = "ISC."+dataSourceType+"." + recipe + "." + table;

                classElement.setAttribute("name", newName);
            } else {
                log.warn(String.format("Name %s does not match expected format, skipping.", originalName));
            }
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(document);
        StreamResult result = new StreamResult(new File(newFilePath));
        transformer.transform(source, result);

        log.info("All Class names updated successfully.");
    }

    private void removeTags() throws ParserConfigurationException, SAXException, IOException, TransformerConfigurationException, TransformerException {
        log.info("removeTags()");

        String[] tagsToRemove = {"Storage", "SqlColumnNumber", "TimeChanged", "TimeCreated"};

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        Document document = builder.parse(new File(newFilePath));
        document.getDocumentElement().normalize();

        for (String tagName : tagsToRemove) {
            NodeList nodeList = document.getElementsByTagName(tagName);

            for (int i = nodeList.getLength() - 1; i >= 0; i--) {
                Node node = nodeList.item(i);

                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    node.getParentNode().removeChild(node);
                }
            }
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(document);
        StreamResult result = new StreamResult(new File(newFilePath));
        transformer.transform(source, result);

        log.info("All SqlColumnNumber and Storage tags with their nested elements were removed successfully.");
    }

    private void updateSuperTags() throws ParserConfigurationException, SAXException, IOException, TransformerConfigurationException, TransformerException {
        log.info("updateSuperTags()");

        String newSuperContent = "%Persistent, User.SalesforceBitemporalColumns";

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        Document document = builder.parse(new File(newFilePath));
        document.getDocumentElement().normalize();  // Normalize the XML structure

        NodeList superList = document.getElementsByTagName("Super");

        for (int i = 0; i < superList.getLength(); i++) {
            Element superElement = (Element) superList.item(i);
            superElement.setTextContent(newSuperContent);
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(document);
        StreamResult result = new StreamResult(new File(newFilePath));
        transformer.transform(source, result);

        log.info("All <Super> tags updated successfully.");
    }

    private void addBitemporalColumnsClass() throws ParserConfigurationException, SAXException, IOException, TransformerConfigurationException, TransformerException {
        log.info("addBitemporalColumnsClass()");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        Document document = builder.parse(new File(newFilePath));
        document.getDocumentElement().normalize();

        Element classElement = document.createElement("Class");
        classElement.setAttribute("name", dataSourceType+"BitemporalColumns");

        classElement.appendChild(document.createElement("Description"));
        createTextElement(document, classElement, "ClassType", "persistent");
        createTextElement(document, classElement, "DdlAllowed", "1");
        createTextElement(document, classElement, "ProcedureBlock", "1");
        createTextElement(document, classElement, "SqlRowIdPrivate", "1");
        createTextElement(document, classElement, "SqlTableName", dataSourceType+"BitemporalColumns");
        createTextElement(document, classElement, "Super", "%Persistent");

        Element createUserProperty = createPropertyElement(document, "CreateUser", "%Library.String", "$USERNAME", "4096", "0");
        classElement.appendChild(createUserProperty);

        Element createTimeStampProperty = createPropertyElement(document, "CreateTimeStamp", "%Library.PosixTime",
                "##class(%Library.PosixTime).CurrentTimeStamp(0)", null, "0");
        classElement.appendChild(createTimeStampProperty);

        Element updateUserProperty = createPropertyElement(document, "UpdateUser", "%Library.String", null, "4096", "0");
        classElement.appendChild(updateUserProperty);

        Element updateTimeStampProperty = createPropertyElement(document, "UpdateTimeStamp", "%Library.PosixTime", null, null, "0");
        classElement.appendChild(updateTimeStampProperty);

        Node root = document.getDocumentElement();
        Node firstClassNode = root.getFirstChild();
        root.insertBefore(classElement, firstClassNode);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
        DOMSource source = new DOMSource(document);
        StreamResult result = new StreamResult(new File(newFilePath));
        transformer.transform(source, result);

        log.info(String.format("BitemporalColumns Class element added successfully.", dataSourceType));

    }

    private void createTextElement(Document document, Element parent, String tagName, String textContent) {
        Element element = document.createElement(tagName);
        element.setTextContent(textContent);
        parent.appendChild(element);
    }

    private Element createPropertyElement(Document document, String name, String type, String initialExpression, String maxLength, String required) {
        Element property = document.createElement("Property");
        property.setAttribute("name", name);

        createTextElement(document, property, "Type", type);
        property.appendChild(document.createElement("Collection"));
        if (initialExpression != null) {
            createTextElement(document, property, "InitialExpression", initialExpression);
        }
        createTextElement(document, property, "Required", required);

        if (maxLength != null) {
            Element parameter = document.createElement("Parameter");
            parameter.setAttribute("name", "MAXLEN");
            parameter.setAttribute("value", maxLength);
            property.appendChild(parameter);
        }

        return property;
    }
}