package com.laker.postman.util;

import lombok.experimental.UtilityClass;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import org.xml.sax.InputSource;

@UtilityClass
public class XmlUtil {
    /**
     * 提取 SOAP 1.1/1.2 中唯一 return 字符串里的业务 XML，并格式化供展示。
     * 只解码 SOAP 文本节点这一层；保留业务 XML 自身的实体转义，不修改原响应。
     * @param response 完整 SOAP 响应；超过 8 Mi 字符、DTD、错误响应或多输出均不转换
     * @return 格式化业务 XML；不适用或解析失败返回 null，调用方继续展示原响应
     */
    public static String extractSoapXml(String response) {
        if (response == null || response.length() > 8 * 1024 * 1024
                || !response.stripLeading().startsWith("<") || Thread.currentThread().isInterrupted()) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                /** 静默拒绝损坏 XML，避免向日志输出响应中的业务内容。 */
                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            Element envelope = builder.parse(new InputSource(new StringReader(response))).getDocumentElement();
            String namespace = envelope.getNamespaceURI();
            if (!"Envelope".equals(envelope.getLocalName())
                    || !("http://schemas.xmlsoap.org/soap/envelope/".equals(namespace)
                    || "http://www.w3.org/2003/05/soap-envelope".equals(namespace))) return null;
            var bodies = envelope.getElementsByTagNameNS(namespace, "Body");
            if (bodies.getLength() != 1 || bodies.item(0).getParentNode() != envelope) return null;
            Element operation = singleElementChild((Element) bodies.item(0));
            if (operation == null || "Fault".equals(operation.getLocalName())) return null;
            Element returned = singleElementChild(operation);
            if (returned == null || !"return".equals(returned.getLocalName())) return null;
            for (Node child = returned.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element) return null;
            }
            // DOM 取文本会解码实体和 CDATA；再次全局反转义会损坏业务 XML 中的 &amp; 等内容。
            String payload = returned.getTextContent().trim();
            if (!payload.startsWith("<") || Thread.currentThread().isInterrupted()) return null;
            Document businessXml = builder.parse(new InputSource(new StringReader(payload)));
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(businessXml), new StreamResult(writer));
            return writer.toString().trim();
        } catch (Exception exception) {
            return null;
        }
    }

    /** 只接受唯一直属元素，避免转换时隐藏其他输出参数或 SOAP 错误详情。 */
    private static Element singleElementChild(Element parent) {
        Element result = null;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                if (result != null) return null;
                result = element;
            }
        }
        return result;
    }

    /**
     * 判断字符串是否为合法 XML
     */
    public static boolean isXml(String str) {
        if (str == null || str.trim().isEmpty()) return false;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.parse(new InputSource(new StringReader(str)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 格式化 XML 字符串，避免重复插入空白行
     */
    public static String formatXml(String xml) {
        if (!isXml(xml)) return xml;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringElementContentWhitespace(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(xml)));

            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            // 去除多余空白行
            String result = writer.toString().replaceAll("(?m)^\s*$", "");
            return result.trim();
        } catch (Exception e) {
            return xml;
        }
    }
}

