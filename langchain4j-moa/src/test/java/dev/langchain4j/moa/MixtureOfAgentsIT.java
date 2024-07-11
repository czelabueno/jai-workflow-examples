package dev.langchain4j.moa;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.moa.internal.DefaultMixtureOfAgents;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModelName;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static java.lang.System.getenv;
import static org.assertj.core.api.Assertions.assertThat;

class MixtureOfAgentsIT {

    // Define reference models, it's recommended instance from 2 to 4 models
    ChatLanguageModel qwen272B = MistralAiChatModel.builder()
            .apiKey(System.getenv("MISTRAL_AI_API_KEY"))
            .modelName(MistralAiChatModelName.MISTRAL_LARGE_LATEST)
            .temperature(0.7)
            .build();

    ChatLanguageModel qwen1572B = MistralAiChatModel.builder()
            .apiKey(System.getenv("MISTRAL_AI_API_KEY"))
            .modelName(MistralAiChatModelName.OPEN_MISTRAL_7B)
            .temperature(0.7)
            .build();

    ChatLanguageModel openMixtral822B = MistralAiChatModel.builder()
            .apiKey(System.getenv("MISTRAL_AI_API_KEY"))
            .modelName(MistralAiChatModelName.OPEN_MIXTRAL_8X22B)
            .temperature(0.7)
            .build();

    ChatLanguageModel claude3Haiku = MistralAiChatModel.builder()
            .apiKey(System.getenv("MISTRAL_AI_API_KEY"))
            .modelName(MistralAiChatModelName.OPEN_MIXTRAL_8x7B)
            .temperature(0.7)
            .build();

    //List<ChatLanguageModel> refLlms = Arrays.asList(mistralLarge, qwen27B, qwen1572B, openaiGpt35);
    List<AgentChatLanguageModel> refLlms = Arrays.asList(
            AgentChatLanguageModel.from("openMixtral822B", openMixtral822B),
            AgentChatLanguageModel.from("claude3Haiku", claude3Haiku),
            AgentChatLanguageModel.from("qwen1572B", qwen1572B),
            AgentChatLanguageModel.from("qwen272B", qwen272B)
    );

    // Define the mixture of agents instance
    MixtureOfAgents moa = DefaultMixtureOfAgents.builder()
            .refLlms(refLlms)
            .numberOfLayers(2)
            .generateLlm(AggregatorChatLanguageModel.from("openMixtral822B",openMixtral822B))
            .workflowImageOutputPath(Paths.get("images/moa-wf-4.svg"))
            .build();

    @Test
    void run_using_builder_mandatory_params(){
        String question = "Top things to do in NYC";
        // when
        String answer = moa.answer(question);
        // then
        assertThat(answer).containsIgnoringWhitespaces("Central Park");
    }

    @Test
    void run_using_generateStreamingModel() {
        // Define the mixture of agents instance
        StreamingChatLanguageModel streamingAnthropic = AnthropicStreamingChatModel.builder()
                .apiKey(getenv("ANTHROPIC_API_KEY"))
                .logRequests(true)
                .logResponses(true)
                .build();

        MixtureOfAgents moa = DefaultMixtureOfAgents.builder()
                .refLlms(refLlms)
                .numberOfLayers(2)
                .generateStreamingLlm(
                        AggregatorStreamingChatLanguageModel.from(
                                "streamingAnthropic",
                                streamingAnthropic))
                .build();

        String question = "Top things to do in NYC";

        // when
        List<String> finalStream = moa.answerStream(question);

        // then
        assertThat(finalStream)
                .anySatisfy(chunk -> {
                    assertThat(chunk).containsIgnoringWhitespaces("Central Park");
                });
    }
}
