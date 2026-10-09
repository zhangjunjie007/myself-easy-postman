package com.laker.postman.collection.importer.wsdl;

import com.laker.postman.collection.model.CollectionNode;
import com.laker.postman.collection.model.CollectionParseResult;
import com.laker.postman.collection.model.RequestGroup;
import com.laker.postman.request.model.HttpHeader;
import com.laker.postman.request.model.HttpRequestItem;
import com.laker.postman.request.model.RequestBodyTypes;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Converts an already-loaded WSDL 1.1/XSD graph to editable SOAP HTTP requests; performs no I/O. */
public final class WsdlCollectionParser {
    public static final String WSDL = "http://schemas.xmlsoap.org/wsdl/";
    public static final String XSD = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final String SOAP11 = WSDL + "soap/";
    private static final String SOAP12 = WSDL + "soap12/";
    private final List<Document> documents;
    private final Map<String, Map<QName, Element>> declarations = new HashMap<>();
    private final Set<QName> activeTypes = new HashSet<>();
    private int generatedElements;
    private int totalGeneratedElements;

    /** Indexes global declarations by namespace and kind so imported documents cannot collide by local name. */
    private WsdlCollectionParser(List<Document> documents) {
        this.documents = documents;
        for (Document document : documents) {
            Element root = document.getDocumentElement();
            if (WSDL.equals(root.getNamespaceURI()) && "definitions".equals(root.getLocalName())) {
                index(root);
                for (Element schema : descendants(root, XSD, "schema")) index(schema);
            } else if (XSD.equals(root.getNamespaceURI()) && "schema".equals(root.getLocalName())) {
                index(root);
            } else {
                throw failure("unsupported");
            }
        }
    }

    /** Parses XML bytes with original encoding; DTD/entity/network resolution is always disabled. */
    public static Document readDocument(byte[] xml, String systemId) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                /** Rejects malformed input without printing XML fragments to stderr. */
                @Override
                public void fatalError(org.xml.sax.SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            Document document = builder.parse(new ByteArrayInputStream(xml), systemId);
            document.setDocumentURI(systemId);
            return document;
        } catch (Exception exception) {
            throw new IOException("collections.import.wsdl.invalid");
        }
    }

    /**
     * Builds a new collection without mutating existing requests. Supports document/rpc literal SOAP
     * 1.1/1.2, schema elements/types and SOAP headers; encoded/attachment bindings fail explicitly.
     * @param documents root WSDL first, followed by its fully loaded WSDL/XSD imports
     */
    public static CollectionParseResult parse(List<Document> documents) {
        if (documents.isEmpty() || !WSDL.equals(documents.get(0).getDocumentElement().getNamespaceURI())) {
            throw failure("unsupported");
        }
        return new WsdlCollectionParser(documents).collection();
    }

    /** Registers only named global declarations; nested schema fields stay scoped to their parent type. */
    private void index(Element owner) {
        String namespace = owner.getAttribute("targetNamespace");
        for (Element child : children(owner, owner.getNamespaceURI(), null)) {
            if (!child.hasAttribute("name")) continue;
            String kind = Set.of("simpleType", "complexType").contains(child.getLocalName()) ? "type" : child.getLocalName();
            QName name = new QName(namespace, child.getAttribute("name"));
            Element previous = declarations.computeIfAbsent(kind, ignored -> new HashMap<>()).putIfAbsent(name, child);
            if (previous != null && !previous.isEqualNode(child)) throw failure("invalid");
        }
    }

    /** Creates one subgroup per SOAP service port; HTTP-only ports are ignored, never converted to SOAP. */
    private CollectionParseResult collection() {
        String name = documents.get(0).getDocumentElement().getAttribute("name");
        CollectionParseResult result = new CollectionParseResult(new RequestGroup(name.isBlank() ? "WSDL" : name));
        int count = 0;
        for (Document document : documents) {
            for (Element service : children(document.getDocumentElement(), WSDL, "service")) {
                for (Element port : children(service, WSDL, "port")) {
                    Element binding = declaration("binding", qname(port, "binding"));
                    Element soapBinding = first(binding, SOAP11, "binding");
                    if (soapBinding == null) soapBinding = first(binding, SOAP12, "binding");
                    if (soapBinding == null) continue;
                    String soap = soapBinding.getNamespaceURI();
                    if (!"http://schemas.xmlsoap.org/soap/http".equals(soapBinding.getAttribute("transport"))) {
                        throw failure("unsupported");
                    }
                    Element address = requiredChild(port, soap, "address");
                    if (address.getAttribute("location").isBlank()) throw failure("invalid");
                    URI endpoint = URI.create(address.getAttribute("location"));
                    if (!endpoint.isAbsolute() && address.getBaseURI() != null) {
                        endpoint = URI.create(address.getBaseURI()).resolve(endpoint);
                    }
                    if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                            || endpoint.getHost() == null) {
                        throw failure("invalid");
                    }
                    CollectionNode group = CollectionNode.group(new RequestGroup(service.getAttribute("name") + " / " + port.getAttribute("name")));
                    Element portType = declaration("portType", qname(binding, "type"));
                    for (Element operation : children(binding, WSDL, "operation")) {
                        if (++count > 1000) throw failure("limits");
                        group.addChild(CollectionNode.request(request(endpoint, binding, soapBinding, portType, operation)));
                    }
                    if (!group.getChildren().isEmpty()) result.addChild(group);
                }
            }
        }
        if (count == 0) throw failure("unsupported");
        return result;
    }

    /** Builds correct version-specific headers and serializes a schema-derived editable envelope. */
    private HttpRequestItem request(URI endpoint, Element binding, Element soapBinding, Element portType, Element operation) {
        String soap = soapBinding.getNamespaceURI();
        Element soapOperation = requiredChild(operation, soap, "operation");
        String action = soapOperation.getAttribute("soapAction");
        if (action.chars().anyMatch(c -> c < 32 || c == 127)) throw failure("invalid");
        if ("true".equals(soapOperation.getAttribute("soapActionRequired")) && action.isEmpty()) throw failure("invalid");
        List<Element> matches = children(portType, WSDL, "operation").stream()
                .filter(element -> operation.getAttribute("name").equals(element.getAttribute("name"))).toList();
        if (matches.size() != 1) throw failure("unsupported");
        Element input = requiredChild(matches.get(0), WSDL, "input");
        Element bindingInput = requiredChild(operation, WSDL, "input");
        Element bodyBinding = requiredChild(bindingInput, soap, "body");
        requireLiteral(bodyBinding);
        Element message = declaration("message", qname(input, "message"));
        String style = soapOperation.hasAttribute("style") ? soapOperation.getAttribute("style") : soapBinding.getAttribute("style");
        if (!style.isEmpty() && !Set.of("document", "rpc").contains(style)) throw failure("unsupported");
        try {
            generatedElements = 0;
            Document xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            String envelopeNamespace = SOAP12.equals(soap) ? "http://www.w3.org/2003/05/soap-envelope" : "http://schemas.xmlsoap.org/soap/envelope/";
            Element envelope = element(xml, envelopeNamespace, "soap:Envelope");
            xml.appendChild(envelope);
            Element header = element(xml, envelopeNamespace, "soap:Header");
            Element body = element(xml, envelopeNamespace, "soap:Body");
            envelope.appendChild(header);
            envelope.appendChild(body);
            for (Element headerBinding : children(bindingInput, soap, "header")) {
                requireLiteral(headerBinding);
                Element headerMessage = declaration("message", qname(headerBinding, "message"));
                Element part = children(headerMessage, WSDL, "part").stream()
                        .filter(p -> p.getAttribute("name").equals(headerBinding.getAttribute("part"))).findFirst().orElseThrow(() -> failure("invalid"));
                appendPart(xml, header, part, false);
            }
            Element payload = body;
            if ("rpc".equals(style)) {
                String namespace = bodyBinding.hasAttribute("namespace") ? bodyBinding.getAttribute("namespace") : binding.getParentNode() instanceof Element definitions ? definitions.getAttribute("targetNamespace") : "";
                payload = element(xml, namespace, operation.getAttribute("name"));
                body.appendChild(payload);
            }
            List<String> parts = bodyBinding.hasAttribute("parts") ? List.of(bodyBinding.getAttribute("parts").trim().split("\\s+")) : null;
            if (parts != null && parts.stream().anyMatch(name -> !name.isEmpty()
                    && children(message, WSDL, "part").stream().noneMatch(part -> name.equals(part.getAttribute("name"))))) {
                throw failure("invalid");
            }
            for (Element part : children(message, WSDL, "part")) {
                if (parts == null || parts.contains(part.getAttribute("name"))) appendPart(xml, payload, part, "rpc".equals(style));
            }
            var factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(xml), new StreamResult(output));
            HttpRequestItem request = new HttpRequestItem();
            request.setId(UUID.randomUUID().toString());
            request.setName(operation.getAttribute("name"));
            request.setUrl(endpoint.toString());
            request.setMethod("POST");
            request.setBodyType(RequestBodyTypes.BODY_TYPE_RAW);
            request.setBody(output.toString());
            String quotedAction = '"' + action.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            if (SOAP12.equals(soap)) {
                request.getHeadersList().add(new HttpHeader(true, "Content-Type", "application/soap+xml; charset=utf-8" + (action.isEmpty() ? "" : "; action=" + quotedAction)));
            } else {
                request.getHeadersList().add(new HttpHeader(true, "Content-Type", "text/xml; charset=utf-8"));
                request.getHeadersList().add(new HttpHeader(true, "SOAPAction", quotedAction));
            }
            return request;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure("invalid");
        }
    }

    /** Expands an element-based message part, or an unqualified rpc parameter with its declared type. */
    private void appendPart(Document xml, Element parent, Element part, boolean rpc) {
        if (!rpc && part.hasAttribute("element")) {
            appendSchemaElement(xml, parent, declaration("element", qname(part, "element")), 0);
        } else if (rpc && part.hasAttribute("type")) {
            Element parameter = element(xml, "", part.getAttribute("name"));
            parent.appendChild(parameter);
            appendType(xml, parameter, qname(part, "type"), 0);
        } else {
            throw failure("unsupported");
        }
    }

    /** Creates representative schema fields, respecting qualification, refs, defaults and required repetition. */
    private void appendSchemaElement(Document xml, Element parent, Element schemaElement, int depth) {
        if ("0".equals(schemaElement.getAttribute("maxOccurs"))) return;
        int repeats = schemaElement.hasAttribute("minOccurs") ? Math.max(1, Integer.parseInt(schemaElement.getAttribute("minOccurs"))) : 1;
        if (repeats > 16 || depth > 64) throw failure("limits");
        boolean optional = "0".equals(schemaElement.getAttribute("minOccurs"));
        if (schemaElement.hasAttribute("ref")) schemaElement = declaration("element", qname(schemaElement, "ref"));
        // Optional recursive branches may be omitted; required cycles cannot produce a finite template.
        if (optional && schemaElement.hasAttribute("type") && activeTypes.contains(qname(schemaElement, "type"))) return;
        Element schema = schemaOwner(schemaElement);
        boolean global = schemaElement.getParentNode() == schema;
        String form = schemaElement.hasAttribute("form") ? schemaElement.getAttribute("form") : schema.getAttribute("elementFormDefault");
        for (int i = 0; i < repeats; i++) {
            Element field = element(xml, global || "qualified".equals(form) ? schema.getAttribute("targetNamespace") : "", schemaElement.getAttribute("name"));
            parent.appendChild(field);
            if (schemaElement.hasAttribute("fixed") || schemaElement.hasAttribute("default")) {
                field.setTextContent(schemaElement.getAttribute(schemaElement.hasAttribute("fixed") ? "fixed" : "default"));
            } else if (schemaElement.hasAttribute("type")) {
                appendType(xml, field, qname(schemaElement, "type"), depth + 1);
            } else {
                appendStructure(xml, field, schemaElement, depth + 1);
                if (!field.hasChildNodes() && first(schemaElement, XSD, "complexType") == null) field.setTextContent("?");
            }
        }
    }

    /** Expands named types or gives built-in scalar types valid starter values; does not validate user data. */
    private void appendType(Document xml, Element field, QName type, int depth) {
        if (depth > 64) throw failure("limits");
        if (XSD.equals(type.getNamespaceURI())) {
            field.setTextContent(switch (type.getLocalPart()) {
                case "boolean" -> "false";
                case "decimal", "integer", "int", "long", "short", "byte", "float", "double", "nonNegativeInteger", "unsignedInt", "unsignedLong", "unsignedShort", "unsignedByte" -> "0";
                case "positiveInteger" -> "1";
                case "negativeInteger", "nonPositiveInteger" -> "-1";
                case "date" -> "2000-01-01";
                case "dateTime" -> "2000-01-01T00:00:00Z";
                case "time" -> "00:00:00";
                case "base64Binary", "hexBinary" -> "";
                case "anyType" -> "";
                default -> "?";
            });
        } else {
            if (!activeTypes.add(type)) throw failure("limits");
            try {
                appendStructure(xml, field, declaration("type", type), depth + 1);
            } finally {
                activeTypes.remove(type);
            }
        }
    }

    /** Traverses schema compositors/types, taking one choice branch; unsupported wildcard/group models fail. */
    private void appendStructure(Document xml, Element field, Element model, int depth) {
        if (depth > 64) throw failure("limits");
        if ("0".equals(model.getAttribute("maxOccurs"))) return;
        if (Set.of("sequence", "all", "choice", "group").contains(model.getLocalName())
                && model.hasAttribute("minOccurs") && Integer.parseInt(model.getAttribute("minOccurs")) > 1) throw failure("unsupported");
        List<Element> models = children(model, XSD, null).stream().filter(child -> !"annotation".equals(child.getLocalName())).toList();
        if ("choice".equals(model.getLocalName()) && !models.isEmpty()) models = models.subList(0, 1);
        for (Element child : models) {
            switch (child.getLocalName()) {
                case "element" -> appendSchemaElement(xml, field, child, depth + 1);
                case "sequence", "all", "choice", "complexType", "simpleType", "complexContent", "simpleContent" -> appendStructure(xml, field, child, depth + 1);
                case "extension", "restriction" -> {
                    // Complex restriction removes base fields; copying the base would create an invalid payload.
                    if ("restriction".equals(child.getLocalName()) && "complexContent".equals(model.getLocalName())) throw failure("unsupported");
                    Element enumeration = first(child, XSD, "enumeration");
                    if (enumeration != null) field.setTextContent(enumeration.getAttribute("value"));
                    else if (child.hasAttribute("base")) appendType(xml, field, qname(child, "base"), depth + 1);
                    appendStructure(xml, field, child, depth + 1);
                }
                case "attribute" -> appendAttribute(xml, field, child, depth + 1);
                case "annotation", "enumeration", "pattern", "minLength", "maxLength", "length", "minInclusive", "maxInclusive", "minExclusive", "maxExclusive", "totalDigits", "fractionDigits", "whiteSpace" -> { }
                default -> throw failure("unsupported");
            }
        }
    }

    /** Generates scalar attribute examples and preserves qualified/global attribute namespaces. */
    private void appendAttribute(Document xml, Element field, Element attribute, int depth) {
        if ("prohibited".equals(attribute.getAttribute("use"))) return;
        if (attribute.hasAttribute("ref")) attribute = declaration("attribute", qname(attribute, "ref"));
        Element sample = xml.createElement("sample");
        if (attribute.hasAttribute("fixed") || attribute.hasAttribute("default")) {
            sample.setTextContent(attribute.getAttribute(attribute.hasAttribute("fixed") ? "fixed" : "default"));
        } else if (attribute.hasAttribute("type")) {
            appendType(xml, sample, qname(attribute, "type"), depth + 1);
        } else if (first(attribute, XSD, "simpleType") != null) {
            appendStructure(xml, sample, attribute, depth + 1);
        } else sample.setTextContent("?");
        Element schema = schemaOwner(attribute);
        String form = attribute.hasAttribute("form") ? attribute.getAttribute("form") : schema.getAttribute("attributeFormDefault");
        String namespace = attribute.getParentNode() == schema || "qualified".equals(form) ? schema.getAttribute("targetNamespace") : "";
        String name = attribute.getAttribute("name");
        field.setAttributeNS(namespace.isEmpty() ? null : namespace,
                namespace.isEmpty() ? name : "a" + field.getAttributes().getLength() + ':' + name, sample.getTextContent());
    }

    /** Resolves lexical QNames using namespace declarations, including chameleon XSD includes. */
    private static QName qname(Element context, String attribute) {
        String value = context.getAttribute(attribute);
        if (value.isBlank()) throw failure("invalid");
        int colon = value.indexOf(':');
        String namespace = context.lookupNamespaceURI(colon < 0 ? null : value.substring(0, colon));
        if (namespace == null && colon >= 0) throw failure("invalid");
        if (namespace == null) {
            namespace = "";
            for (Node node = context; node instanceof Element element; node = node.getParentNode()) {
                if (XSD.equals(element.getNamespaceURI()) && "schema".equals(element.getLocalName())) {
                    namespace = element.getAttribute("targetNamespace");
                    break;
                }
            }
        }
        return new QName(namespace, colon < 0 ? value : value.substring(colon + 1));
    }

    /** Finds the containing schema; only schema declarations may use the no-prefix namespace fallback. */
    private static Element schemaOwner(Element context) {
        for (Node node = context; node instanceof Element element; node = node.getParentNode()) {
            if (XSD.equals(element.getNamespaceURI()) && "schema".equals(element.getLocalName())) return element;
        }
        throw failure("invalid");
    }

    /** Requires a resolved declaration rather than silently generating an empty or incorrectly named payload. */
    private Element declaration(String kind, QName name) {
        Element result = declarations.getOrDefault(kind, Map.of()).get(name);
        if (result == null) throw failure("invalid");
        return result;
    }

    /** Requires literal serialization; encoded SOAP and MIME attachments need a different generator. */
    private static void requireLiteral(Element binding) {
        if (!"literal".equals(binding.getAttribute("use"))) throw failure("unsupported");
    }

    /** Creates a bounded XML template; DOM serialization handles namespaces and escapes values safely. */
    private Element element(Document document, String namespace, String name) {
        if (++generatedElements > 4096 || ++totalGeneratedElements > 100_000) throw failure("limits");
        Element element = namespace.isEmpty() ? document.createElement(name) : document.createElementNS(namespace, name);
        // Explicit resets keep unqualified fields unqualified beneath an element with a default namespace.
        if (!name.contains(":")) element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", namespace);
        return element;
    }

    /** Returns direct children only so nested operations/types cannot be mistaken for global declarations. */
    private static List<Element> children(Element parent, String namespace, String name) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && (name == null || name.equals(element.getLocalName()))) result.add(element);
        }
        return result;
    }

    /** Locates inline schema roots under wsdl:types without depending on the document's namespace prefixes. */
    private static List<Element> descendants(Element parent, String namespace, String name) {
        List<Element> result = new ArrayList<>();
        var nodes = parent.getElementsByTagNameNS(namespace, name);
        for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
        return result;
    }

    /** Returns an optional direct child of a specific namespace. */
    private static Element first(Element parent, String namespace, String name) {
        return children(parent, namespace, name).stream().findFirst().orElse(null);
    }

    /** Rejects missing binding/message pieces rather than synthesizing an unusable request. */
    private static Element requiredChild(Element parent, String namespace, String name) {
        Element result = first(parent, namespace, name);
        if (result == null) throw failure("invalid");
        return result;
    }

    /** Supplies stable error keys; host UI owns translations and never displays raw input XML or URLs. */
    private static IllegalArgumentException failure(String reason) {
        return new IllegalArgumentException("collections.import.wsdl." + reason);
    }
}
