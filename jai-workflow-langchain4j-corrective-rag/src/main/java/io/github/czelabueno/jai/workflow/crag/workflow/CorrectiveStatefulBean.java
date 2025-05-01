package io.github.czelabueno.jai.workflow.crag.workflow;

import io.github.czelabueno.jai.workflow.langchain4j.AbstractStatefulBean;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@ToString(callSuper = true)
public class CorrectiveStatefulBean extends AbstractStatefulBean {

    private String webSearch;
    private List<String> documents;

    public CorrectiveStatefulBean() {
    }
}
