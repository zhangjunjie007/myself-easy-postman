package com.laker.postman.request.model;

import lombok.Data;

import java.io.Serializable;

/** Last imported operation contract and generated template, independent of user-edited request values. */
@Data
public class RequestImportMetadata implements Serializable {
    private static final long serialVersionUID = 1L;
    private String operationId = "";
    private String path = "";
    private String method = "";
    private String definition = "";
    private String template = "";
}
