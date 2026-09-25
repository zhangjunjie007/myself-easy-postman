package com.laker.postman.codegen.json;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

@Getter
@RequiredArgsConstructor
public enum JsonModelLanguage {
    JAVA("Java", SyntaxConstants.SYNTAX_STYLE_JAVA),
    TYPESCRIPT("TypeScript", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
    CSHARP("C#", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS);

    private final String displayName;
    private final String syntaxStyle;

    @Override
    public String toString() {
        return displayName;
    }
}
