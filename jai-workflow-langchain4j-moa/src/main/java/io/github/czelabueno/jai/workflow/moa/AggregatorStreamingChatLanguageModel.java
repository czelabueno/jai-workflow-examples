package io.github.czelabueno.jai.workflow.moa;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

/**
 * This class represents an aggregator streaming chat language model.
 * It provides methods to get the name and the model.
 */
public class AggregatorStreamingChatLanguageModel {

    private String name;
    private StreamingChatLanguageModel model;

    private AggregatorStreamingChatLanguageModel(String name, StreamingChatLanguageModel model) {
        this.name = ensureNotNull(name,"name");
        this.model = ensureNotNull(model,"model");
    }

    public String name() {
        return name;
    }

    public StreamingChatLanguageModel model() {
        return model;
    }

    public static AggregatorStreamingChatLanguageModel from(String name, StreamingChatLanguageModel llm) {
        return new AggregatorStreamingChatLanguageModel(name, llm);
    }

}
