package com.laker.postman.model;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** Environment-owned PLM login settings. Defaults are plaintext by the workspace owner's choice. */
@Getter
@Setter
public class PlmAuthConfig {
    private String type;
    private String tokenUrl;
    private String method;
    private String clientId;
    private String clientSecret;
    private String grantType;
    private String principalHeader;
    private String principalPrefix;
    private String principal;
    private String username;
    private String password;
    private String pin;
    private String pinLocation;
    private String pinName;
    private Object bodyTemplate;
    private String tokenJsonPath;
    private String headerName;
    private String headerPrefix;
    private Map<String, String> headers;
}
