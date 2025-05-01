package io.github.czelabueno.jai.workflow.moa.internal;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.czelabueno.jai.workflow.DefaultStateWorkflow;
import io.github.czelabueno.jai.workflow.graph.graphviz.GraphvizImageGenerator;
import io.github.czelabueno.jai.workflow.langchain4j.JAiWorkflow;
import io.github.czelabueno.jai.workflow.langchain4j.internal.DefaultJAiWorkflow;
import io.github.czelabueno.jai.workflow.langchain4j.node.StreamingNode;
import io.github.czelabueno.jai.workflow.moa.AgentChatLanguageModel;
import io.github.czelabueno.jai.workflow.moa.AggregatorChatLanguageModel;
import io.github.czelabueno.jai.workflow.moa.AggregatorStreamingChatLanguageModel;
import io.github.czelabueno.jai.workflow.moa.MixtureOfAgents;
import io.github.czelabueno.jai.workflow.moa.workflow.MoaNodeFunctions;
import io.github.czelabueno.jai.workflow.moa.workflow.MoaStatefulBean;
import io.github.czelabueno.jai.workflow.node.Node;
import io.github.czelabueno.jai.workflow.transition.Transition;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.IntStream;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static io.github.czelabueno.jai.workflow.WorkflowStateName.END;
import static io.github.czelabueno.jai.workflow.moa.workflow.graph.graphviz.GeneratorDotFormat.moaDotFormat;
import static java.util.stream.Collectors.toList;

/**
 * Default implementation of {@link MixtureOfAgents}.
 * <p>
 * The Mixture-of-Agents methodology leverages multiple models to boost performance, capitalizing on the collaborative nature of Large Language Models (LLMs).
 * LLMs can be categorized into two roles: Proposers and Aggregators. Proposers generate useful reference responses, providing context and diverse perspectives.
 * Aggregators synthesize responses from other models into a single, high-quality output. The methodology enhances this collaborative potential by introducing additional aggregators,
 * allowing for iterative synthesis and refinement of responses. This leads to superior outcomes by leveraging the strengths of multiple models.
 * <p>
 * This class is responsible for generating a <a href="https://arxiv.org/pdf/2406.04692">Mixture of Agents (MOA) architecture </a>
 * using a list of reference language models and an aggregator language model.
 * The MOA architecture consists of a number of layers, each containing a number of agents
 * and an aggregator. The agents in each layer propose answers and the aggregator in the
 * layer generates a final answer. The final answer is then passed to the next layer of agents
 * and aggregators, which further refine the answer. The process is repeated until the last
 * layer, where a final aggregator generates the final answer.
 * <p>
 * It's represented by a workflow of nodes, each representing an agent or an aggregator, that
 * process the user question and generate a final answer.
 * <p>
 * The agents and aggregator are represented by {@link AgentChatLanguageModel} and
 * {@link AggregatorChatLanguageModel} or {@link AggregatorStreamingChatLanguageModel} respectively.
 * This class uses the Builder pattern for construction.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * MixtureOfAgents moa = DefaultMixtureOfAgents.builder()
 *     .refLlms(refLlms)
 *     .numberOfLayers(3)
 *     .generateLlm(AggregatorChatLanguageModel.from("mistralLarge",mistralLarge))
 *     .workflowImageOutputPath(Paths.get("images/moa-wf-3.svg"))
 *     .build();
 * }
 * </pre>
 */
public class DefaultMixtureOfAgents implements MixtureOfAgents {

    private static final Logger log = LoggerFactory.getLogger(DefaultMixtureOfAgents.class);
    private final List<AgentChatLanguageModel> refLlms;
    private final Integer numberOfLayers;
    private final Optional<AggregatorStreamingChatLanguageModel> generateStreamingLlm;
    private final Optional<AggregatorChatLanguageModel> generateLlm;
    private final Boolean stream;
    private final Boolean generateWorkflowImage;
    private final Path workflowImageOutputPath;

    /**
     * Constructor for DefaultMixtureOfAgents.
     *
     * @param refLlms                 List of reference language models (ChatLanguageModel).
     * @param numberOfLayers          Number of layers in the MOA architecture.
     *                                <p>
     *                                Default value: 1
     * @param generateStreamingLlm    AggregatorStreamingChatLanguageModel to generate final answer.
     *                                <p>
     *                                If this is no provided. generateLlm must be provided.
     * @param generateLlm             AggregatorChatLanguageModel to generate final answer.
     *                                <p>
     *                                If this is no provided. generateStreamingLlm must be provided.
     * @param stream                  Boolean indicating if the workflow should run in stream mode.
     *                                <p>
     *                                Default value: false
     * @param generateWorkflowImage   Boolean indicating if a workflow image should be generated.
     *                                <p>
     *                                Default value: false
     * @param workflowImageOutputPath Path for the output of the workflow image.
     *                                <p>
     *                                If this is provided generateWorkflowImage is set to true.
     */
    @Builder
    public DefaultMixtureOfAgents(List<AgentChatLanguageModel> refLlms,
                                  Integer numberOfLayers, 
                                  AggregatorStreamingChatLanguageModel generateStreamingLlm,
                                  AggregatorChatLanguageModel generateLlm,
                                  Boolean stream, 
                                  Boolean generateWorkflowImage, 
                                  Path workflowImageOutputPath) {
        this.generateStreamingLlm = Optional.ofNullable(generateStreamingLlm);
        this.generateLlm = Optional.ofNullable(generateLlm);
        if (generateStreamingLlm == null && generateLlm == null) {
            throw new IllegalArgumentException("Either generateLlm or generateStreamingLlm must be provided");
        }
        if (generateStreamingLlm != null && generateLlm != null) {
            throw new IllegalArgumentException("Both generateLlm and generateStreamingLlm cannot be provided");
        }

        this.refLlms = ensureNotNull(
                getOrDefault(refLlms, DefaultMixtureOfAgents::defaultRefLlms),
                "refLlms");
        this.numberOfLayers = getOrDefault(numberOfLayers, 1);
        this.stream = getOrDefault(stream, false);

        // Check if workflowOutputPath is valid
        this.generateWorkflowImage = workflowImageOutputPath != null || Boolean.TRUE.equals(generateWorkflowImage);
        this.workflowImageOutputPath = workflowImageOutputPath;
    }

    @Override
    public AiMessage answer(UserMessage question) {
        // Build MOA workflow
        JAiWorkflow wf = moaWorkflow(new MoaStatefulBean());

        AiMessage aiAnswer = wf.answer(question);

        // Generate workflow image
        if (generateWorkflowImage) {
            try {
                if (workflowImageOutputPath != null) {
                    wf.getWorkflowImage(workflowImageOutputPath);
                } else {
                    wf.generateComputedWorkflowImage();
                }
            } catch (Exception e) {
                log.warn("Error generating workflow image", e);
            }
        }
        return aiAnswer;
    }

    @Override
    public Flux<String> answerStream(UserMessage question) {
        // Build MOA workflow
        JAiWorkflow wf = moaWorkflow(new MoaStatefulBean());

        // Generate workflow image
        if (generateWorkflowImage) {
            try {
                if (workflowImageOutputPath != null) {
                    wf.getWorkflowImage(workflowImageOutputPath);
                } else {
                    wf.generateComputedWorkflowImage();
                }
            } catch (Exception e) {
                log.warn("Error generating workflow image", e);
            }
        }
        return wf.answerStream(question);
    }

    private JAiWorkflow moaWorkflow(MoaStatefulBean statefulBean) {
        log.info("=== Generating MOA architecture.. ===");
        Map<Integer, List<Node<MoaStatefulBean,MoaStatefulBean>>> layers = new ConcurrentHashMap<>();
        MoaNodeFunctions moaNodeFunctions = new MoaNodeFunctions();
        // Create nodes for each layer and agent
        IntStream.rangeClosed(1, numberOfLayers).forEach(iLayer -> {
            List<Node<MoaStatefulBean, MoaStatefulBean>> nodes =
                    IntStream.rangeClosed(1, refLlms.size())
                            .mapToObj(iLlm -> createAgentNode(iLayer, iLlm, refLlms.get(iLlm - 1), moaNodeFunctions))
                            .collect(toList());
            log.debug("  === Created Layer: [" + iLayer + "], Nodes added [" + nodes.size() + "] ===");
            layers.putIfAbsent(iLayer, nodes);
        });

        // Create aggregator node
        Node<MoaStatefulBean, ?> aggregatorNode = createAggregatorNode(statefulBean, moaNodeFunctions);
        log.debug("  === Created Aggregator Node ===");

        // Build workflow
        JAiWorkflow wf = buildWorkflow(statefulBean, layers, aggregatorNode);

        log.info("=== MOA architecture generated ===");
        log.info("  === Layers: [" + layers.size() + "], Agents: [" + layers.values().stream().mapToInt(List::size).sum() + "] ===");
        log.info("  === Agent Aggregator: [" + aggregatorNode.getName() + "] ===");
        log.info("Parsing MOA architecture to workflow...");
        return wf;
    }

    private Node<MoaStatefulBean, MoaStatefulBean> createAgentNode(int iLayer, int iLlm, AgentChatLanguageModel refLlm, MoaNodeFunctions moaNodeFunctions) {
        Function<MoaStatefulBean, MoaStatefulBean> proposerAgent = obj -> moaNodeFunctions.proposerAgent(obj, refLlm, iLayer);
        return Node.from("AgentNode " + iLayer + "." + iLlm + ": " + refLlm.name(), proposerAgent);
    }

    private Node<MoaStatefulBean, ?> createAggregatorNode(MoaStatefulBean statefulBean, MoaNodeFunctions moaNodeFunctions) {
        if (generateStreamingLlm.isPresent()) {
            StreamingNode<MoaStatefulBean> streamingNode = StreamingNode.from(
                    "AggregatorStreamingNode: " + generateStreamingLlm.get().name(),
                    moaNodeFunctions.aggregatorStreamingAgent(statefulBean),
                    generateStreamingLlm.get().model()
            );
            return streamingNode;
        } else {
            Function<MoaStatefulBean, MoaStatefulBean> aggregator = obj -> moaNodeFunctions.aggregatorAgent(obj, generateLlm.get());
            return Node.from("AggregatorNode: " + generateLlm.get().name(), aggregator);
        }
    }

    private JAiWorkflow buildWorkflow(
            MoaStatefulBean statefulBean,
            Map<Integer, List<Node<MoaStatefulBean,MoaStatefulBean>>> layers,
            Node<MoaStatefulBean, ?> aggregatorNode) {

        // Define edges between nodes
        List<Transition> transitions = new ArrayList<>();
        for (int iLayer = 1; iLayer <= layers.size(); iLayer++) {
            List<Node<MoaStatefulBean, MoaStatefulBean>> nodes = layers.get(iLayer);
            for (int iNode = 0; iNode < nodes.size(); iNode++) {
                Node<MoaStatefulBean, MoaStatefulBean> currentNode = nodes.get(iNode);
                Node<MoaStatefulBean, ?> nextNode = getNextNode(iLayer, iNode, nodes, aggregatorNode, layers);
                transitions.add(Transition.from(currentNode, nextNode));
            }
        }
        transitions.add(Transition.from(aggregatorNode, END));

        // Set moaDotFormat
//        if (generateWorkflowImage) //TODO: Works only after workflow runs. Find a way to set custom dotFormat in builder
//            wf.setGraphImageGenerator(GraphvizImageGenerator.<MoaStatefulBean>builder()
//                    .dotFormat(moaDotFormat(wf.getComputedTransitions(),layers))
//                    .build());

        // Build workflow definition
        return new DefaultJAiWorkflow<MoaStatefulBean>(
                statefulBean,
                transitions,
                layers.get(1).get(0), // first node
                stream);
    }

    private Node<MoaStatefulBean, ?> getNextNode(
            int iLayer,
            int iNode,
            List<Node<MoaStatefulBean, MoaStatefulBean>> nodes,
            Node<MoaStatefulBean, ?> aggregatorNode,
            Map<Integer, List<Node<MoaStatefulBean,MoaStatefulBean>>> layers) {
        if (iNode < nodes.size() - 1) {
            // If the current node is not the last node in the current layer
            return nodes.get(iNode + 1);
        } else if (iLayer < layers.size()) {
            // If the current node is the last node in the current layer and there is a next layer
            return layers.get(iLayer + 1).get(0);
        } else {
            // If the current node is the last node in the last layer
            return aggregatorNode;
        }
    }

    // TODO - Implement default Llms using localAI or ollama
    private static List<AgentChatLanguageModel> defaultRefLlms(){
        return null;
    }
}
