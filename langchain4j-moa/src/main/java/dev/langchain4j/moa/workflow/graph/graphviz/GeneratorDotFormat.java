package dev.langchain4j.moa.workflow.graph.graphviz;

import dev.langchain4j.moa.workflow.MoaStatefulBean;
import dev.langchain4j.workflow.WorkflowStateName;
import dev.langchain4j.workflow.node.Node;
import dev.langchain4j.workflow.transition.Transition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GeneratorDotFormat {
    public static String moaDotFormat(List<Transition<MoaStatefulBean>> transitions, Map<Integer, List<Node<MoaStatefulBean,MoaStatefulBean>>> computedLayers){
        StringBuilder sb = new StringBuilder();
        Map<Integer, List<String>> layerTransitions = new HashMap<>();
        sb.append("digraph moaWorkflow {").append(System.lineSeparator());
        sb.append("\t").append("rankdir=LR;").append(System.lineSeparator());
        sb.append(System.lineSeparator());
        for (Map.Entry<Integer, List<Node<MoaStatefulBean,MoaStatefulBean>>> entry : computedLayers.entrySet()) {
            layerTransitions.put(entry.getKey(), new ArrayList<>(entry.getValue().size()));
            sb.append("\t").append("subgraph cluster_"+entry.getKey()+" {").append(System.lineSeparator());
            sb.append("\t\t").append("label=<<b>Layer "+entry.getKey()+"</b>>;").append(System.lineSeparator());
            sb.append("\t\t").append("style=filled;").append(System.lineSeparator());
            sb.append("\t\t").append("color=lightgrey;").append(System.lineSeparator());
            sb.append("\t\t").append("node [style=filled,color=white];").append(System.lineSeparator());
            String layertransition = "";
            for (Integer iNode = 1; iNode <= entry.getValue().size(); iNode++) {
                Node<MoaStatefulBean,MoaStatefulBean> node = entry.getValue().get(iNode-1);
                String nodeKey = "A"+entry.getKey()+"_"+iNode;
                sb.append("\t\t").append(nodeKey+" ")
                        .append("[label=\""+ node.getName().replace(":"," \n")+"\"];")
                        .append(System.lineSeparator());
                layerTransitions.get(entry.getKey()).add(nodeKey);
                if (iNode == entry.getValue().size()){
                    layertransition += nodeKey+";";
                } else {
                    layertransition += nodeKey+" -> ";
                }
            }
            sb.append("\t\t").append("{rank=same; "+layertransition+"}").append(System.lineSeparator());
            sb.append("\t").append("}").append(System.lineSeparator());
            sb.append(System.lineSeparator());
        }
        int aggregatorLayerNumber = computedLayers.size() + 1;
        sb.append("\t").append("subgraph cluster_"+aggregatorLayerNumber+" {").append(System.lineSeparator());
        sb.append("\t\t").append("label=<<b>Layer "+aggregatorLayerNumber+"</b>>;").append(System.lineSeparator());
        sb.append("\t\t").append("style=filled;").append(System.lineSeparator());
        sb.append("\t\t").append("fillcolor=white;").append(System.lineSeparator());
        sb.append("\t\t").append("color=blue;").append(System.lineSeparator());
        sb.append("\t\t").append("node [fillcolor=\"lightgray:lightgreen\" style=filled gradientangle=270 fontcolor=\"black\" fontsize=15.5];").append(System.lineSeparator());
        Node aggregatorNode = (Node) transitions.stream()
                .filter(transition -> transition.getTo() == WorkflowStateName.END)
                .findFirst()
                .map(Transition::getFrom)
                .orElseThrow(() -> new IllegalArgumentException("No aggregator node found"));
        String lastTransition = "A"+aggregatorLayerNumber+"_1";
        sb.append("\t\t").append(lastTransition)
                .append("[label=\"Aggregator agent "+aggregatorLayerNumber+".1"+" \\n "+ aggregatorNode.getName()+"\"];")
                .append(System.lineSeparator());
        sb.append("\t").append("}").append(System.lineSeparator());
        sb.append(System.lineSeparator());
        // Connections between subgraphs using HTML-like labels
        sb.append("\t").append(WorkflowStateName.START.toString().toLowerCase()+" -> A1_1;").append(System.lineSeparator());
        for (int i = 0; i < layerTransitions.get(1).size(); i++) {
            for (int j = 1; j < layerTransitions.size(); j++) {
                sb.append("\t");
                sb.append("A").append(j).append("_").append(i+1);
                sb.append(" -> ");
                sb.append("A").append(j+1).append("_").append(i+1);
                sb.append(";\n");
            }
            if (i == layerTransitions.get(1).size() - 1) {
                sb.append("\t");
                sb.append("A").append(layerTransitions.size()).append("_").append(i+1);
                sb.append(" -> "+lastTransition);
                sb.append(";\n");
            }
        }
        lastTransition += " -> "+ WorkflowStateName.END.toString().toLowerCase()+";";
        sb.append("\t").append(lastTransition).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        // Start and End node styles
        sb.append("\t")
                .append(WorkflowStateName.START.toString().toLowerCase()+" [shape=Mdiamond, fillcolor=\"orange\", style=filled, label=\"START\"];")
                .append(System.lineSeparator());
        sb.append("\t")
                .append(WorkflowStateName.END.toString().toLowerCase()+" [shape=Msquare, fillcolor=\"green\", style=filled, label=\"END\"];")
                .append(System.lineSeparator());
        sb.append("}");
        return sb.toString();
    }
}
