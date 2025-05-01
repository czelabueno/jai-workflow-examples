package io.github.czelabueno.jai.workflow.moa.workflow;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.czelabueno.jai.workflow.moa.AgentChatLanguageModel;
import io.github.czelabueno.jai.workflow.moa.AggregatorChatLanguageModel;
import io.github.czelabueno.jai.workflow.moa.AggregatorStreamingChatLanguageModel;
import io.github.czelabueno.jai.workflow.moa.prompt.AggregateSynthesizePrompt;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.structured.StructuredPromptProcessor;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class MoaNodeFunctions {

    private static final Logger log = LoggerFactory.getLogger(MoaNodeFunctions.class);
    private final Map<ChatLanguageModel, AiMessage> chatLanguageModelResponses = new HashMap<>();

    public MoaStatefulBean proposerAgent(MoaStatefulBean state,
                                         AgentChatLanguageModel agentChatLanguageModel,
                                         Integer layer){
        log.info("---PROPOSER AGENT: Layer (" + layer + "), AgentModel: " + agentChatLanguageModel.name() + "---");
        log.debug("--- Input: " + state.toString());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(state.getQuestion()));

        log.debug("--- Layer (" + layer + "), ChatLanguageModelResponses: " + chatLanguageModelResponses.toString());
        ChatLanguageModel chatLanguageModel = agentChatLanguageModel.model();
        if (chatLanguageModelResponses.containsKey(chatLanguageModel))
            messages = injectReferencesAsSystemMessage(messages, chatLanguageModelResponses.get(chatLanguageModel).text());

        AiMessage output = chatLanguageModel.generate(messages).content();
        log.info("--- Proposer Response: " + output.toString());
        chatLanguageModelResponses.put(chatLanguageModel, output); // override the previous response
        state.setCurrentLayer(layer);
        return state;
    }

    public MoaStatefulBean aggregatorAgent(MoaStatefulBean state,
                                           AggregatorChatLanguageModel chatLanguageModel) {
        log.info("---AGGREGATE MODEL: " + chatLanguageModel.getClass().getName() + "---");
        state.setReferences(getReferences());
        log.debug("--- Input: " + state.toString());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(state.getQuestion()));
        if (!state.getReferences().isEmpty())
            messages = injectReferencesAsSystemMessage(messages, state.getReferences());

        AiMessage finalAnswer = chatLanguageModel.model().generate(messages).content();
        state.setGeneration(finalAnswer.text());
        log.debug("--- Output: " + state.toString());
        return state;
    }

    public List<ChatMessage> aggregatorStreamingAgent(MoaStatefulBean state) {
        state.setReferences(getReferences());
        log.debug("--- Input: " + state.toString());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(state.getQuestion()));
        if (!state.getReferences().isEmpty())
            messages = injectReferencesAsSystemMessage(messages, state.getReferences());
        return messages;
    }

    private List<ChatMessage> injectReferencesAsSystemMessage(List<ChatMessage> messages, String... references) {
        return injectReferencesAsSystemMessage(messages, Arrays.asList(references));
    }
    private List<ChatMessage> injectReferencesAsSystemMessage(List<ChatMessage> messages, List<String> references) {
        List<ChatMessage> injectedMessages = new ArrayList<>();
        AggregateSynthesizePrompt synthesizePrompt = new AggregateSynthesizePrompt(references);
        Prompt systemPrompt = StructuredPromptProcessor.toPrompt(synthesizePrompt);

        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                String systemMessageContent = ((SystemMessage) message).text();
                SystemMessage systemMessage = new SystemMessage(systemMessageContent + "\n\n" + systemPrompt.text());
                injectedMessages.add(systemMessage);
                break;
            } else {
                injectedMessages.add(0, systemPrompt.toSystemMessage());
                injectedMessages.add(message);
            }
        }
        log.debug("  --- Injected References: " + injectedMessages.toString());
        return injectedMessages;
    }

    private List<String> getReferences() {
        return chatLanguageModelResponses.values().stream().map(AiMessage::text).collect(Collectors.toList());
    }
}
