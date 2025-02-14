/*
 * ACFRTransformerFactory.java - This file is part of ACFR.
 * Copyright 2019 Thomas Peterson <thpeter@kth.se>
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, see <http://www.gnu.org/licenses/>.
 */
package org.jakstab.cfa;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedList;

import java.io.FileWriter;

import org.jakstab.Options;
import org.jakstab.Program;
import org.jakstab.analysis.AbstractState;
import org.jakstab.analysis.UnderApproximateState;
import org.jakstab.analysis.composite.DualCompositeState;
import org.jakstab.asm.AbsoluteAddress;
import org.jakstab.cfa.CFAEdge.Kind;
import org.jakstab.rtl.Context;
import org.jakstab.cfa.RTLLabel;
import org.jakstab.rtl.expressions.ExpressionFactory;
import org.jakstab.rtl.expressions.RTLExpression;
import org.jakstab.rtl.expressions.RTLNumber;
import org.jakstab.rtl.statements.*;
import org.jakstab.util.FastSet;
import org.jakstab.util.Logger;
import org.jakstab.util.Tuple;

/**
 * Provides state transformers without assumptions about procedures. Call instructions
 * are treated as push / jmp combinations and return instructions as indirect jumps. If
 * the target of a jump cannot be resolved, send the path to this jump to an under-approximation algorithm.
 * Then, wait for the under-approximation algorithm to finish. Thereafter, use the obtained targets
 * to create MUST edges.
 *
 * @author Thomas Peterson
 */
public class ACFRTransformerFactory extends ResolvingTransformerFactory {

    private Program program = Program.getProgram();

    private static final Logger logger = Logger.getLogger(ACFRTransformerFactory.class);

    private Map<RTLNumber, RTLNumber> realToStub = new HashMap<RTLNumber, RTLNumber>();
    private Map<RTLNumber, RTLNumber> stubToReal = new HashMap<RTLNumber, RTLNumber>();

    @Override
    // Returns the transformers of an abstract state
    public Set<CFAEdge> getTransformers(final AbstractState a) {
        //Load the statement of the abstract state a
        RTLStatement stmt = Program.getProgram().getStatement((RTLLabel)a.getLocation());

        //Fetch the transformers from the statement
        Set<CFAEdge> transformers = stmt.accept(new DefaultStatementVisitor<Set<CFAEdge>>() {

            @Override
            protected Set<CFAEdge> visitDefault(RTLStatement stmt) {

                CFAEdge.Kind edgeKind = Kind.MAY;

                // If an under-approximate component is not BOT, then it is a witness and should become a MUST edge
                DualCompositeState dcs = (DualCompositeState)a;
                for (int i = 0; i < dcs.numComponents(); i++) {
                    AbstractState componentState = dcs.getComponent(i);
                    if (componentState instanceof UnderApproximateState && !componentState.isBot()) {
                        edgeKind = Kind.MUST;
                        break;
                    }
                }

                return Collections.singleton(new CFAEdge(stmt.getLabel(), stmt.getNextLabel(), stmt, edgeKind));
            }

            @Override
            public Set<CFAEdge> visit(RTLGoto stmt) {

                //Assert that this is in fact a goto statement
                assert stmt.getCondition() != null;

                //Interpret the abstract state as a DualCompositeState
                DualCompositeState dcs = (DualCompositeState)a;

                //The set of edges which will contain the resulting edges
                Set<CFAEdge> results = new FastSet<CFAEdge>();

                //Optimistic mode
                if (Options.procedureAbstraction.getValue() == 2) {
                    if (stmt.getType() == RTLGoto.Type.CALL) {
                        //Add fall-through edge
                        RTLLabel nextLabel = stmt.getNextLabel();

                        if (Program.getProgram().getHarness().contains(stmt.getAddress())) {
                            nextLabel = new RTLLabel(Program.getProgram().getHarness().getFallthroughAddress(stmt.getAddress()));
                        }

                        if (nextLabel != null) {
                            RTLUnknownProcedureCall unknownCallEdge = new RTLUnknownProcedureCall(stmt);
                            unknownCallEdge.setLabel(stmt.getLabel());
                            unknownCallEdge.setNextLabel(nextLabel);
                            results.add(new CFAEdge(stmt.getLabel(), nextLabel, unknownCallEdge));
                            sound = false;
                        }
                    }
                    else if (stmt.getType() == RTLGoto.Type.RETURN) {
                        // Return statements are replaced with halt statements
                        // as control flow can pass over calls anyways
                        sound = false;
                        return Collections.emptySet();
                    }
                }

                // Add all edges from over-approximation
                for (Tuple<RTLNumber> pair : dcs.projectionFromConcretization(stmt.getCondition(), stmt.getTargetExpression())) {
                    RTLNumber conditionValue = pair.get(0);
                    RTLNumber targetValue = pair.get(1);
                    RTLLabel nextLabel;

                    // Start building the assume expression: assume correct condition case
                    assert conditionValue != null;
                    RTLExpression assumption = ExpressionFactory.createEqual(stmt.getCondition(), conditionValue);

                    if (conditionValue.equals(ExpressionFactory.FALSE)) {
                        // assume (condition = false), and set next statement to fallthrough
                        nextLabel = stmt.getNextLabel();
                    } else {
                        if (targetValue == null) {
                            // If the target of the jump can not be determined
                            sound = false;

                            unresolvedBranches.add(stmt.getLabel());//Used for statistics

                            continue;
                        }
                        // assume (condition = true AND targetExpression = targetValue)
                        assumption = ExpressionFactory.createAnd(
                                assumption,
                                ExpressionFactory.createEqual(
                                        stmt.getTargetExpression(),
                                        targetValue)
                        );
                        // set next label to jump target
                        nextLabel = new RTLLabel(new AbsoluteAddress(targetValue));
                    }
                    assumption = assumption.evaluate(new Context());
                    RTLAssume assume = new RTLAssume(assumption, stmt);
                    assume.setLabel(stmt.getLabel());
                    assume.setNextLabel(nextLabel);

                    // Target address sanity check
                    if (nextLabel.getAddress().getValue() < 10L) {
                        logger.warn("Control flow from " + stmt.getLabel() + " reaches address " + nextLabel.getAddress() + "!");
                    }

                    results.add(new CFAEdge(assume.getLabel(), assume.getNextLabel(), assume, Kind.MAY));
                }
                return results;
            }

            @Override
            public Set<CFAEdge> visit(RTLHalt stmt) {
                return Collections.emptySet();
            }

        });

        saveNewEdges(transformers, (RTLLabel)a.getLocation());

        return transformers;
    }

    @Override
    protected void saveNewEdges(Set<CFAEdge> transformers, RTLLabel l) {
        // Make sure we only add new edges. Edges are mutable so we cannot just implement
        // hashCode and equals and add everything into a HashSet.
        for (CFAEdge edge : transformers) {
            boolean found = false;
            // We check for this in the loop, because transformers may contain duplicate edges
            // that only differ in their kind. So we check them against each other for upgrading
            if (outEdges.containsKey(l)) {
                for (CFAEdge existingEdge : outEdges.get(l)) {
                    if (existingEdge.getTarget().equals(edge.getTarget())) {

                        // There is an edge with the same target
                        found = true;

                        // Different kinds of edges
                        if (!existingEdge.getKind().equals(edge.getKind())) {

                            if (existingEdge.getKind().lessOrEqual(edge.getKind())) {
                                // If the new kind is greater than the existing, upgrade to new kind
                                //logger.debug("Upgrading existing edge " + existingEdge + " from " + existingEdge.getKind() + " to " + edge.getKind());
                                existingEdge.setKind(edge.getKind());
                            } else if (edge.getKind().lessOrEqual(existingEdge.getKind())) {
                                // If the existing kind is greater than the new one, upgrade new one
                                //logger.debug("Upgrading new edge " + edge + " from " + edge.getKind() + " to " + existingEdge.getKind());
                                edge.setKind(existingEdge.getKind());
                            } else {
                                // Incomparable edge kinds cannot happen with current logic
                                assert false : "Incomparable edge kinds!";
                            }
                        }

                        //break;
                    }
                }
            }
            if (!found) outEdges.put(l,  edge);
        }

    }

    @Override
    protected Set<CFAEdge> resolveGoto(AbstractState a, RTLGoto stmt) {
        throw new UnsupportedOperationException("Not used");
    }

    private boolean isProgramAddress(RTLNumber n) {
        return program.getModule(new AbsoluteAddress(n.longValue())) != null;
    }

}
