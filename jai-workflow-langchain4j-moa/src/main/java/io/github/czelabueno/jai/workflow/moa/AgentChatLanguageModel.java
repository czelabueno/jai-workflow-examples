package io.github.czelabueno.jai.workflow.moa;

import dev.langchain4j.model.chat.ChatLanguageModel;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

/**
 * This class represents an agent chat language model.
 * It provides methods to get the name and the model.
 */
public class AgentChatLanguageModel {
    private final String name;
    private final ChatLanguageModel model;

    private AgentChatLanguageModel(String name, ChatLanguageModel model) {
        this.name = ensureNotNull(name, "name");
        this.model = ensureNotNull(model, "model");
    }

    public String name() {
        return name;
    }

    public ChatLanguageModel model() {
        return model;
    }

    public static AgentChatLanguageModel from(String name, ChatLanguageModel llm) {
        return new AgentChatLanguageModel(name, llm);
    }
}
