package io.github.czelabueno.jai.workflow.crag.internal;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.content.retriever.WebSearchContentRetriever;
import io.github.czelabueno.jai.workflow.crag.CorrectiveRag;
import io.github.czelabueno.jai.workflow.crag.workflow.CorrectiveNodeFunctions;
import io.github.czelabueno.jai.workflow.crag.workflow.CorrectiveStatefulBean;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.czelabueno.jai.workflow.WorkflowStateName;
import io.github.czelabueno.jai.workflow.langchain4j.JAiWorkflow;
import io.github.czelabueno.jai.workflow.langchain4j.internal.DefaultJAiWorkflow;
import io.github.czelabueno.jai.workflow.node.Conditional;
import io.github.czelabueno.jai.workflow.node.Node;
import io.github.czelabueno.jai.workflow.transition.Transition;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

public class DefaultCorrectiveRag implements CorrectiveRag {

    private static final Logger log = LoggerFactory.getLogger(DefaultCorrectiveRag.class);

    private final EmbeddingStoreContentRetriever embeddingStoreContentRetriever;
    private final WebSearchContentRetriever webSearchContentRetriever;
    private final ChatLanguageModel chatLanguageModel;
    private final Boolean stream;
    private final Boolean generateWorkflowImage;
    private final Path workflowImageOutputPath;
    

    @Builder
    public DefaultCorrectiveRag(EmbeddingStoreContentRetriever embeddingStoreContentRetriever,
                                WebSearchContentRetriever webSearchContentRetriever,
                                ChatLanguageModel chatLanguageModel,
                                List<Document> documents,
                                Boolean stream,
                                Boolean generateWorkflowImage,
                                Path workflowImageOutputPath
                                ) {
        if (documents.isEmpty() && embeddingStoreContentRetriever == null) {
            throw new IllegalArgumentException("documents or embeddingStoreContentRetriever must be provided");
        }
        this.embeddingStoreContentRetriever = ensureNotNull(
                getOrDefault(embeddingStoreContentRetriever, DefaultCorrectiveRag.defaultContentRetriever(documents)),
                "embeddingStoreContentRetriever"
        );
        this.webSearchContentRetriever = ensureNotNull(webSearchContentRetriever, "webSearchContentRetriever");
        this.chatLanguageModel = ensureNotNull(chatLanguageModel, "chatLanguageModel");
        this.stream = getOrDefault(stream, false);

        // Check if workflowOutputPath is valid
        if (workflowImageOutputPath != null) {
            this.workflowImageOutputPath = workflowImageOutputPath;
            this.generateWorkflowImage = true;
        } else {
            this.workflowImageOutputPath = null;
            this.generateWorkflowImage = getOrDefault(generateWorkflowImage, false);
        }

    }

    @Override
    public AiMessage answer(UserMessage question) {
        // Build corrective workflow
        JAiWorkflow wf = correctiveWorkflow(new CorrectiveStatefulBean());

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

    private JAiWorkflow correctiveWorkflow(CorrectiveStatefulBean statefulBean) {
        // Create wrapper functions for nodes
        CorrectiveNodeFunctions cwf = new CorrectiveNodeFunctions.Builder()
                .withEmbeddingStoreContentRetriever(embeddingStoreContentRetriever)
                .withChatLanguageModel(chatLanguageModel)
                .withWebSearchContentRetriever(webSearchContentRetriever)
                .build();
        // Define functions for nodesß
        Function<CorrectiveStatefulBean, CorrectiveStatefulBean> retrieve = state -> cwf.retrieve(statefulBean);
        Function<CorrectiveStatefulBean, CorrectiveStatefulBean> generate = state -> cwf.generate(statefulBean);
        Function<CorrectiveStatefulBean, CorrectiveStatefulBean> gradeDocuments = state -> cwf.gradeDocuments(statefulBean);
        Function<CorrectiveStatefulBean, CorrectiveStatefulBean> rewriteQuery = state -> cwf.transformQuery(statefulBean);
        Function<CorrectiveStatefulBean, CorrectiveStatefulBean> webSearch = state -> cwf.webSearch(statefulBean);
        // Create nodes
        Node<CorrectiveStatefulBean, CorrectiveStatefulBean> retrieveNode = Node.from("Retrieve Node", retrieve);
        Node<CorrectiveStatefulBean, CorrectiveStatefulBean> generateNode = Node.from("Generate Node", generate);
        Node<CorrectiveStatefulBean, CorrectiveStatefulBean> gradeDocumentsNode = Node.from("Grade Node", gradeDocuments);
        Node<CorrectiveStatefulBean, CorrectiveStatefulBean> rewriteQueryNode = Node.from("Re-Write Query Node", rewriteQuery);
        Node<CorrectiveStatefulBean, CorrectiveStatefulBean> webSearchNode = Node.from("WebSearch Node", webSearch);
        // Create transitions
        Transition retrieveToGrade = Transition.from(retrieveNode, gradeDocumentsNode);
        Transition requireWebSearch = Transition.from(gradeDocumentsNode, Conditional.<CorrectiveStatefulBean>eval("webSearch?",
                obj -> {
                    if (obj.getWebSearch().equals("Yes")) {
                        log.info("---DECISION: ALL DOCUMENTS ARE NOT RELEVANT TO QUESTION, TRANSFORM QUERY---");
                        return rewriteQueryNode;
                    } else {
                        log.info("---DECISION: GENERATE---");
                        return generateNode;
                    }
                },
                Arrays.asList(rewriteQueryNode, generateNode)
        ));
        Transition rewriteToWebSearch = Transition.from(rewriteQueryNode, webSearchNode);
        Transition webSearchToGenerate = Transition.from(webSearchNode, generateNode);
        Transition generateToEnd = Transition.from(generateNode, WorkflowStateName.END);


        // Build workflow
        return new DefaultJAiWorkflow<CorrectiveStatefulBean>(
                statefulBean,
                Arrays.asList(
                        retrieveToGrade,
                        requireWebSearch,
                        rewriteToWebSearch,
                        webSearchToGenerate,
                        generateToEnd
                ),
                retrieveNode,
                stream);
    }

    private static EmbeddingStoreContentRetriever defaultContentRetriever(List<Document> documents) {
        EmbeddingModel embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();
        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
        log.info("Using defaultContentRetriever, embeddingModel:{} embeddingStore:{}", embeddingModel.getClass().getName(), embeddingStore.getClass().getName());
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(250, 0))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        ingestor.ingest(documents);
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .minScore(0.6)
                .build();
    }

}
