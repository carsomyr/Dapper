/**
 * This file is part of Dapper, the Distributed and Parallel Program Execution Runtime ("this library"). <br />
 * <br />
 * Copyright (C) 2008 Roy Liu, The Regents of the University of California <br />
 * <br />
 * This library is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 2.1 of the License, or (at your option)
 * any later version. <br />
 * <br />
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details. <br />
 * <br />
 * You should have received a copy of the GNU Lesser General Public License along with this library. If not, see <a
 * href="http://www.gnu.org/licenses/">http://www.gnu.org/licenses/</a>.
 */

package dapper.server.flow;

import static dapper.Constants.BLACK;
import static dapper.Constants.CODELET_RETRIES;
import static dapper.Constants.CODELET_TIMEOUT;
import static dapper.Constants.DARK_BLUE;
import static dapper.Constants.DARK_GREEN;
import static dapper.Constants.DARK_ORANGE;
import static dapper.Constants.DARK_RED;
import static dapper.Constants.LIGHT_BLUE;

import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.regex.Pattern;

import org.w3c.dom.Node;

import shared.parallel.Traversable;
import shared.util.Control;
import dapper.client.ClientStatus;
import dapper.codelet.Codelet;
import dapper.codelet.CodeletUtilities;
import dapper.codelet.Nameable;
import dapper.codelet.ParameterMetadata;
import dapper.codelet.Resource;
import dapper.codelet.Taggable;
import dapper.event.ResourceEvent;
import dapper.server.ClientState;
import dapper.server.flow.FlowEdge.FlowEdgeType;
import dapper.util.Requirement;

/**
 * A node class for storing {@link Codelet} computation state.
 * 
 * @author Roy Liu
 */
public class FlowNode implements Traversable<FlowNode, FlowEdge>, ParameterMetadata, Cloneable, Renderable,
        Requirement<ClientState>, Nameable, Taggable<Object> {

    /**
     * An empty parameters DOM {@link Node}.
     */
    final public static Node EmptyParameters = CodeletUtilities.createElement("");

    final Codelet codelet;

    Node parameters;

    long timeout;

    int order, depth, currentRetries, retries;

    Pattern domainPattern;

    String name;

    Object attachment;

    //

    List<FlowEdge> in;
    List<FlowEdge> out;

    LogicalNode logicalNode;

    ClientState clientState;

    /**
     * Default constructor.
     */
    protected FlowNode(Codelet codelet) {

        this.codelet = codelet;

        this.depth = (this.order = -1);
        this.currentRetries = 0;

        this.timeout = CODELET_TIMEOUT;
        this.retries = CODELET_RETRIES;
        this.domainPattern = null;
        this.name = "";
        this.parameters = EmptyParameters;

        //

        this.in = new ArrayList<FlowEdge>();
        this.out = new ArrayList<FlowEdge>();

        this.logicalNode = null;
        this.clientState = null;
    }

    /**
     * Alternate constructor.
     */
    public FlowNode(String className) {
        this(loadCodelet(className));
    }

    /**
     * Loads a {@link Codelet} from {@link Thread#getContextClassLoader()}.
     */
    final public static Codelet loadCodelet(String className) {

        try {

            return (Codelet) Thread.currentThread() //
                    .getContextClassLoader().loadClass(className).newInstance();

        } catch (Exception e) {

            throw new RuntimeException(e);
        }
    }

    /**
     * Compares two {@link FlowNode}s on the basis of their assigned DFS order.
     */
    public int compareTo(FlowNode node) {
        return this.order - node.order;
    }

    /**
     * Creates a {@link FlowNode} with this node's settings.
     */
    @Override
    public FlowNode clone() {

        final FlowNode res;

        try {

            res = (FlowNode) super.clone();

        } catch (CloneNotSupportedException e) {

            throw new RuntimeException(e);
        }

        res.in = new ArrayList<FlowEdge>();
        res.out = new ArrayList<FlowEdge>();

        res.logicalNode = null;

        if (this.clientState != null) {

            res.clientState = this.clientState.clone();
            res.clientState.setFlowNode(res);

        } else {

            res.clientState = null;
        }

        return res;
    }

    public int getOrder() {
        return this.order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public int getDepth() {
        return this.depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public List<FlowEdge> getIn() {
        return this.in;
    }

    public List<FlowEdge> getOut() {
        return this.out;
    }

    public boolean isSatisfied(ClientState satisfier) {
        return this.domainPattern.matcher(satisfier.getDomain()).matches();
    }

    public boolean isTrivial() {
        return this.domainPattern == null;
    }

    /**
     * Gets the {@link Codelet}.
     */
    public Codelet getCodelet() {
        return this.codelet;
    }

    /**
     * Sets the domain {@link Pattern}.
     */
    public FlowNode setDomainPattern(String patternString) {

        this.domainPattern = Pattern.compile(patternString);

        return this;
    }

    /**
     * Gets the number of allowable retries.
     */
    public int getRetries() {
        return this.retries;
    }

    /**
     * Gets the number of allowable retries.
     */
    public FlowNode setRetries(int retries) {

        this.retries = retries;

        return this;
    }

    /**
     * Gets the timeout in milliseconds.
     */
    public long getTimeout() {
        return this.timeout;
    }

    /**
     * Sets the timeout in milliseconds.
     */
    public FlowNode setTimeout(long timeout) {

        this.timeout = timeout;

        return this;
    }

    public Object getAttachment() {
        return this.attachment;
    }

    public FlowNode setAttachment(Object attachment) {

        this.attachment = attachment;

        return this;
    }

    public Node getParameters() {
        return this.parameters;
    }

    /**
     * Sets the parameters DOM {@link Node}.
     */
    public FlowNode setParameters(Node parameters) {

        Control.checkTrue(parameters != null && parameters.getNodeName().equals("parameters"), //
                "Invalid parameters node");

        this.parameters = parameters;

        return this;
    }

    /**
     * Sets the parameters DOM {@link Node} whose internals are given by a string.
     */
    public FlowNode setParameters(String content) {

        setParameters(CodeletUtilities.createElement(content));

        return this;
    }

    public String getName() {
        return this.name;
    }

    public FlowNode setName(String name) {

        Control.checkTrue(name != null, //
                "Name must be non-null");

        this.name = name;

        return this;
    }

    /**
     * If set, gets the name; if not, delegates to the underlying {@link Codelet}'s {@link Codelet#toString()} method.
     */
    @Override
    public String toString() {
        return !this.name.equals("") ? this.name : this.codelet.toString();
    }

    /**
     * Creates a {@link ResourceEvent}.
     */
    public ResourceEvent createResourceEvent() {

        List<Resource> inResources = new ArrayList<Resource>();
        List<Resource> outResources = new ArrayList<Resource>();

        for (FlowEdge edge : getIn()) {

            if (edge.getType() != FlowEdgeType.DUMMY) {
                inResources.add(edge.createVResource());
            }
        }

        for (FlowEdge edge : getOut()) {

            if (edge.getType() != FlowEdgeType.DUMMY) {
                outResources.add(edge.createUResource());
            }
        }

        return new ResourceEvent(inResources, outResources, //
                this.codelet.getClass().getName(), //
                this.parameters, null);
    }

    /**
     * Gets the {@link ClientState}.
     */
    public ClientState getClientState() {
        return this.clientState;
    }

    /**
     * Sets the {@link ClientState}.
     */
    public void setClientState(ClientState clientState) {
        this.clientState = clientState;
    }

    /**
     * Gets the {@link LogicalNode}.
     */
    public LogicalNode getLogicalNode() {
        return this.logicalNode;
    }

    /**
     * Sets the {@link LogicalNode}.
     */
    public void setLogicalNode(LogicalNode logicalNode) {
        this.logicalNode = logicalNode;
    }

    /**
     * Increments and gets the number of retries.
     */
    public int incrementAndGetRetries() {
        return ++this.currentRetries;
    }

    public void render(Formatter f) {

        final String color;

        ClientStatus status = (this.clientState != null) ? this.clientState.getStatus() : null;

        if (status != null) {

            switch (status) {

            case EXECUTE:
                color = DARK_ORANGE;
                break;

            case RESOURCE:
            case RESOURCE_ACK:
            case PREPARE:
            case PREPARE_ACK:
                color = LIGHT_BLUE;
                break;

            default:
                color = BLACK;
                break;
            }

        } else {

            switch (getLogicalNode().getStatus()) {

            case PENDING:
                color = DARK_BLUE;
                break;

            case EXECUTE:
            case FINISHED:
                color = DARK_GREEN;
                break;

            case FAILED:
                color = DARK_RED;
                break;

            default:
                color = BLACK;
                break;
            }
        }

        f.format("%n\tnode_%s [%n", this.order);
        f.format("\t\tlabel = \"%s\",%n", toString().replace("\"", "\\\""));
        f.format("\t\tstyle = \"%s\",%n", this.codelet instanceof EmbeddingCodelet ? "dotted" : "solid");
        f.format("\t\tcolor = \"#%s\"%n", color);
        f.format("\t];%n");
    }
}
