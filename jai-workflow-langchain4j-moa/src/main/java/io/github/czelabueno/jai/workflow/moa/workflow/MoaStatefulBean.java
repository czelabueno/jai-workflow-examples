package io.github.czelabueno.jai.workflow.moa.workflow;

import io.github.czelabueno.jai.workflow.langchain4j.AbstractStatefulBean;
import lombok.Data;

import java.util.List;

@Data
public class MoaStatefulBean extends AbstractStatefulBean {

    private Integer currentLayer;
    private List<String> references; // for aggregator and proposers

    public MoaStatefulBean() {
    }

    @Override
    public String toString() {
        return "MoaStatefulBean{" +
                "question='" + getQuestion() + '\'' +
                ", currentN=" + currentLayer +
                ", references=" + references +
                ", generation='" + getGeneration() + '\'' +
                '}';
    }
}
