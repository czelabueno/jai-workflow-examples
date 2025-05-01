package io.github.czelabueno.jai.workflow.moa;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import reactor.core.publisher.Flux;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

/**
 * This interface represents a mixture of agents.
 * It provides methods to get answers from the agents.
 */
public interface MixtureOfAgents {

    /**
     * Provides an answer to a given question.
     * @param question The question to be answered.
     * @return The answer to the question.
     */
    default String answer(String question){
        ensureNotNull(question, "question");
        return answer(new UserMessage(question)).text();
    }

    /**
     * Provides an AI message as an answer to a given user message.
     * @param question The user message containing the question.
     * @return The AI message containing the answer.
     */
    AiMessage answer(UserMessage question);

    /**
     * Provides a list of answers to a given question in a streaming manner.
     * This method is used when the `AggregatorStreamingChattLanguageModel` is injected.
     * It serves to generate a final answer in streaming and it will be listed as List of string.
     * @param question The question to be answered.
     * @return The Flux stream of answers.
     */
    default Flux<String> answerStream(String question){
        ensureNotNull(question, "question");
        return answerStream(new UserMessage(question));
    }

    /**
     * Provides a list of answers to a given user message in a streaming manner.
     * This method is used when the `AggregatorStreamingChattLanguageModel` is injected.
     * It serves to generate a final answer in streaming and it will be listed as List of string.
     * @param question The user message containing the question.
     * @return The Flux stream of answers.
     */
    Flux<String> answerStream(UserMessage question);
}
