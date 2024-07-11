package dev.langchain4j.moa.prompt;

import dev.langchain4j.model.input.structured.StructuredPrompt;

import java.util.List;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;

@StructuredPrompt({
        "You have been provided with a set of responses from various open-source models to the latest user query.\n",
        "Your task is to synthesize these responses into a single, high-quality response. It is crucial to critically evaluate the ",
        "information provided in these responses, recognizing that some of it may be biased or incorrect. \n",
        "Your response should not simply replicate the given answers but should offer a refined, accurate, and comprehensive reply ",
        "to the instruction. Ensure your response is well-structured, coherent, and adheres to the highest standards of accuracy and reliability. \n",
        "Responses from models: \n",
        "{{modelResponses}}"
})
public class AggregateSynthesizePrompt {

    private List<String> modelResponses;

    public AggregateSynthesizePrompt(List<String> modelResponses) {
        this.modelResponses = modelResponses;
    }

    public List<String> getModelResponses() {
        return IntStream.range(0, modelResponses.size())
                .mapToObj(i -> (i + 1) + ". " + modelResponses.get(i))
                .collect(toList());
    }
}
