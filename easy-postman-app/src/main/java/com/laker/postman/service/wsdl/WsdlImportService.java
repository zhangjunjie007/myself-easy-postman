package com.laker.postman.service.wsdl;

import com.laker.postman.collection.importer.wsdl.WsdlCollectionParser;
import com.laker.postman.collection.model.CollectionParseResult;
import lombok.experimental.UtilityClass;
import okhttp3.OkHttpClient;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Loads user-selected WSDL files/URLs and their declared imports before neutral collection parsing. */
@UtilityClass
public class WsdlImportService {
    private static final int MAX_DOCUMENT_BYTES = 8 * 1024 * 1024;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build();

    /**
     * Loads at most 64 documents/32 MiB within a 60-second import deadline. Called off the EDT.
     * Remote WSDLs may import HTTP(S) resources, never local files; no business token is sent on downloads.
     * @param source an absolute file, HTTP or HTTPS URI supplied by the user
     * @return a complete collection; missing/unsupported dependencies abort before UI insertion
     */
    public static CollectionParseResult importWsdl(URI source) throws IOException {
        Map<String, Document> documents = new LinkedHashMap<>();
        try {
            load(source.normalize(), "", !"file".equalsIgnoreCase(source.getScheme()), documents,
                    new int[]{0}, System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
            return WsdlCollectionParser.parse(new ArrayList<>(documents.values()));
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage();
            throw new IOException(message != null && message.startsWith("collections.import.wsdl.")
                    ? message : "collections.import.wsdl.invalid");
        }
    }

    /**
     * Registers each URI/namespace before following imports to break cycles. The namespace key allows
     * chameleon XSD includes under distinct target namespaces without mixing declarations.
     */
    private static void load(URI source, String includedNamespace, boolean remoteRoot,
                             Map<String, Document> documents, int[] totalBytes, long deadline) throws IOException {
        String key = source + "\n" + includedNamespace;
        if (documents.containsKey(key)) return;
        long remaining = deadline - System.nanoTime();
        if (documents.size() >= 64 || remaining <= 0 || Thread.currentThread().isInterrupted()) throw failure("limits");
        String scheme = source.getScheme() == null ? "" : source.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("file", "http", "https").contains(scheme) || source.getUserInfo() != null
                || (remoteRoot && "file".equals(scheme))) throw failure("invalid");
        byte[] bytes;
        URI resolvedSource = source;
        if ("file".equals(scheme)) {
            try (var input = Files.newInputStream(Path.of(source))) {
                bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
            }
        } else {
            OkHttpClient client = CLIENT.newBuilder().callTimeout(Math.min(remaining, TimeUnit.SECONDS.toNanos(20)), TimeUnit.NANOSECONDS).build();
            try (var response = client.newCall(new Request.Builder().url(source.toString()).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw failure("download_failed");
                resolvedSource = response.request().url().uri();
                try (var input = response.body().byteStream()) {
                    bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
                }
            } catch (IOException exception) {
                // HTTP exceptions can contain signed URLs; only a translated failure key leaves the loader.
                throw failure("download_failed");
            }
        }
        totalBytes[0] += bytes.length;
        if (bytes.length > MAX_DOCUMENT_BYTES || totalBytes[0] > 32 * 1024 * 1024
                || System.nanoTime() > deadline) throw failure("limits");
        Document document = WsdlCollectionParser.readDocument(bytes, resolvedSource.toString());
        Element root = document.getDocumentElement();
        if (WsdlCollectionParser.XSD.equals(root.getNamespaceURI()) && root.getAttribute("targetNamespace").isEmpty()
                && !includedNamespace.isEmpty()) root.setAttribute("targetNamespace", includedNamespace);
        documents.put(key, document);
        var elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element reference = (Element) elements.item(i);
            boolean wsdlImport = WsdlCollectionParser.WSDL.equals(reference.getNamespaceURI()) && "import".equals(reference.getLocalName());
            boolean schemaImport = WsdlCollectionParser.XSD.equals(reference.getNamespaceURI())
                    && Set.of("import", "include", "redefine").contains(reference.getLocalName());
            if (!wsdlImport && !schemaImport) continue;
            if ("redefine".equals(reference.getLocalName())) throw failure("unsupported");
            String location = reference.getAttribute(wsdlImport ? "location" : "schemaLocation");
            if (location.isBlank()) continue; // Namespace-only imports may already be declared inline.
            URI base = reference.getBaseURI() == null ? resolvedSource : URI.create(reference.getBaseURI());
            String namespace = "include".equals(reference.getLocalName()) && reference.getParentNode() instanceof Element schema
                    ? schema.getAttribute("targetNamespace") : "";
            URI dependency = URI.create(location);
            if (!dependency.isAbsolute()) {
                if ("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme())) {
                    HttpUrl url = HttpUrl.get(base).resolve(location);
                    if (url == null) throw failure("invalid");
                    dependency = url.uri();
                } else dependency = base.resolve(dependency);
            }
            load(dependency.normalize(), namespace, remoteRoot || !"file".equals(scheme), documents, totalBytes, deadline);
        }
    }

    /** Supplies localized failure keys without leaking XML bodies, credentials or signed URLs. */
    private static IOException failure(String reason) {
        return new IOException("collections.import.wsdl." + reason);
    }
}
