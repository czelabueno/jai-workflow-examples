package dev.langchain4j.moa.internal;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.moa.AgentChatLanguageModel;
import dev.langchain4j.moa.AggregatorChatLanguageModel;
import dev.langchain4j.moa.AggregatorStreamingChatLanguageModel;
import dev.langchain4j.moa.MixtureOfAgents;
import dev.langchain4j.moa.workflow.MoaNodeFunctions;
import dev.langchain4j.moa.workflow.graph.graphviz.GeneratorDotFormat;
import dev.langchain4j.moa.workflow.MoaStatefulBean;
import dev.langchain4j.workflow.DefaultStateWorkflow;
import dev.langchain4j.workflow.WorkflowStateName;
import dev.langchain4j.workflow.graph.graphviz.GraphvizImageGenerator;
import dev.langchain4j.workflow.node.Node;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.IntStream;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static java.util.stream.Collectors.toList;

/**
 * Default implementation of {@link dev.langchain4j.moa.MixtureOfAgents}.
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
 * The agents and aggregator are represented by {@link dev.langchain4j.moa.AgentChatLanguageModel} and
 * {@link dev.langchain4j.moa.AggregatorChatLanguageModel} or {@link dev.langchain4j.moa.AggregatorStreamingChatLanguageModel} respectively.
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
    private Map<Integer, List<Node<MoaStatefulBean,MoaStatefulBean>>> layers;

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
        MoaStatefulBean statefulBean = processQuestion(question);
        return AiMessage.from(statefulBean.getGeneration());
    }

    @Override
    public List<String> answerStream(UserMessage question) {
        MoaStatefulBean statefulBean = processQuestion(question);
        return statefulBean.getGeneratedStream();
    }

    private MoaStatefulBean processQuestion(UserMessage question) {
        MoaStatefulBean statefulBean = new MoaStatefulBean();
        statefulBean.setQuestion(question.singleText());

        // Build moa workflow
        DefaultStateWorkflow<MoaStatefulBean> wf = moaWorkflow(statefulBean);

        // Run workflow in stream mode or not
        if (stream) {
            log.info("Running workflow in stream mode...");
            wf.runStream(node -> log.debug("Processing node: " + node.getName()));
        } else {
            log.info("Running workflow in normal mode...");
            wf.run();
        }

        // Print transitions
        log.debug("Transitions: \n" + wf.prettyTransitions() + "\n");

        // Generate workflow image
        if (generateWorkflowImage) {
            try {
                wf.setGraphImageGenerator(GraphvizImageGenerator.<MoaStatefulBean>builder()
                        .dotFormat(GeneratorDotFormat.moaDotFormat(wf.getComputedTransitions(),layers))
                        .build());
                generateWorkflowImage(wf);
            } catch (Exception e) {
                log.warn("Error generating workflow image", e);
            }
        }
        return statefulBean;
    }

    private DefaultStateWorkflow<MoaStatefulBean> moaWorkflow (MoaStatefulBean statefulBean){
        log.info("=== Generating MOA architecture.. ===");
        layers = new ConcurrentHashMap<>();
        MoaNodeFunctions moaNodeFunctions = new MoaNodeFunctions();
        // Create nodes for each layer and agent
        IntStream.rangeClosed(1, numberOfLayers).forEach(iLayer -> {
            List<Node<MoaStatefulBean, MoaStatefulBean>> nodes = IntStream.rangeClosed(1, refLlms.size())
                    .mapToObj(iLlm -> createAgentNode(iLayer, iLlm, refLlms.get(iLlm - 1), moaNodeFunctions))
                    .collect(toList());
            log.debug("  === Created Layer: [" + iLayer + "], Nodes added [" + nodes.size() + "] ===");
            layers.putIfAbsent(iLayer, nodes);
        });

        // Create aggregator node
        Node<MoaStatefulBean, MoaStatefulBean> aggregatorNode = createAggregatorNode(moaNodeFunctions);
        log.debug("  === Created Aggregator Node ===");

        // Build workflow
        DefaultStateWorkflow<MoaStatefulBean> wf = buildWorkflow(statefulBean, aggregatorNode);

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

    private Node<MoaStatefulBean, MoaStatefulBean> createAggregatorNode(MoaNodeFunctions moaNodeFunctions) {
        if (generateStreamingLlm.isPresent()) {
            Function<MoaStatefulBean, MoaStatefulBean> aggregator = obj -> moaNodeFunctions.aggregatorStreamingAgent(obj, generateStreamingLlm.get());
            return Node.from("AggregatorStreamingNode: " + generateStreamingLlm.get().name(), aggregator);
        } else {
            Function<MoaStatefulBean, MoaStatefulBean> aggregator = obj -> moaNodeFunctions.aggregatorAgent(obj, generateLlm.get());
            return Node.from("AggregatorNode: " + generateLlm.get().name(), aggregator);
        }
    }

    private DefaultStateWorkflow<MoaStatefulBean> buildWorkflow(MoaStatefulBean statefulBean, Node<MoaStatefulBean, MoaStatefulBean> aggregatorNode) {
        DefaultStateWorkflow<MoaStatefulBean> wf = DefaultStateWorkflow.<MoaStatefulBean>builder()
                .statefulBean(statefulBean)
                .addNodes(layers.values().stream().flatMap(List::stream).collect(toList()))
                .addNode(aggregatorNode)
                .build();

        // Define edges between nodes
        for (int iLayer = 1; iLayer <= layers.size(); iLayer++) {
            List<Node<MoaStatefulBean, MoaStatefulBean>> nodes = layers.get(iLayer);
            for (int iNode = 0; iNode < nodes.size(); iNode++) {
                Node<MoaStatefulBean, MoaStatefulBean> currentNode = nodes.get(iNode);
                Node<MoaStatefulBean, MoaStatefulBean> nextNode = getNextNode(iLayer, iNode, nodes, aggregatorNode);
                wf.putEdge(currentNode, nextNode);
            }
        }
        wf.putEdge(aggregatorNode, WorkflowStateName.END);

        // Define node entrypoint
        wf.startNode(layers.get(1).get(0));

        return wf;
    }

    private Node<MoaStatefulBean, MoaStatefulBean> getNextNode(int iLayer, int iNode, List<Node<MoaStatefulBean, MoaStatefulBean>> nodes, Node<MoaStatefulBean, MoaStatefulBean> aggregatorNode) {
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

    private void generateWorkflowImage(DefaultStateWorkflow<MoaStatefulBean> wf) throws IOException {
        if (workflowImageOutputPath != null) {
            wf.generateWorkflowImage(workflowImageOutputPath.toAbsolutePath().toString());
        } else {
            wf.generateWorkflowImage();
        }
    }
}
