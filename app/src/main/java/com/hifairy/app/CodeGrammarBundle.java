package com.hifairy.app;

import io.noties.prism4j.annotations.PrismBundle;

@PrismBundle(
    include = {"clike", "java", "kotlin", "python", "javascript", "json", "markup", "css", "sql", "c", "cpp"},
    grammarLocatorClassName = ".CodeGrammarLocator"
)
public final class CodeGrammarBundle {
    public static io.noties.prism4j.Prism4j create() {
        return new io.noties.prism4j.Prism4j(new CodeGrammarLocator());
    }
}
