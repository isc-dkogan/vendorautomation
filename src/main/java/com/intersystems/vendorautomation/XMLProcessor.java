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

    public void transformXML(List<String> stagingTableNames) {
        try {
            String filePath = "allclasses.xml";
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
}