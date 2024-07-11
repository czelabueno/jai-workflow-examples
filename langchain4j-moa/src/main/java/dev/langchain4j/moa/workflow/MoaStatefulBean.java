package dev.langchain4j.moa.workflow;

import dev.langchain4j.service.TokenStream;
import lombok.Data;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.Utils.isNullOrEmpty;

@Data
public class MoaStatefulBean {

    private String question;
    private Integer currentLayer;
    private List<String> references; // for aggregator and proposers
    private String generation;
    private List<String> generatedStream;

    public MoaStatefulBean() {
    }

    public String getGeneration() {
        if (!isNullOrEmpty(generatedStream) && generation == null) {
            generation = generatedStream.stream().collect(Collectors.joining());
        }
        return generation;
    }

    @Override
    public String toString() {
        return "MoaStatefulBean{" +
                "question='" + question + '\'' +
                ", currentN=" + currentLayer +
                ", references=" + references +
                ", generation='" + generation + '\'' +
                '}';
    }
}
