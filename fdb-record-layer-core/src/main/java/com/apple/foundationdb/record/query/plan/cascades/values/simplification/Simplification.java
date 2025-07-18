/*
 * Simplification.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2022 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.query.plan.cascades.values.simplification;

import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.query.plan.QueryPlanConstraint;
import com.apple.foundationdb.record.query.plan.RecordQueryPlannerConfiguration;
import com.apple.foundationdb.record.query.plan.cascades.AliasMap;
import com.apple.foundationdb.record.query.plan.cascades.Constrained;
import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.LinkedIdentityMap;
import com.apple.foundationdb.record.query.plan.cascades.PlannerRule;
import com.apple.foundationdb.record.query.plan.cascades.TreeLike;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.BindingMatcher;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.PlannerBindings;
import com.apple.foundationdb.record.query.plan.cascades.predicates.QueryPredicate;
import com.apple.foundationdb.record.query.plan.cascades.predicates.simplification.QueryPredicateComputationRuleSet;
import com.apple.foundationdb.record.query.plan.cascades.predicates.simplification.QueryPredicateSimplificationRuleCall;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;
import com.apple.foundationdb.record.util.pair.NonnullPair;
import com.apple.foundationdb.record.util.pair.Pair;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * Main class of a mini rewrite engine to simplify (or to compute over)
 * {@link com.apple.foundationdb.record.query.plan.cascades.values.Value} trees.
 */
public class Simplification {
    /**
     * Main function that simplifies the given value using the {@link AbstractValueRuleSet} passed in.
     * @param root the {@link Value} to be simplified
     * @param evaluationContext the evaluation context
     * @param aliasMap an alias map of equalities
     * @param constantAliases a set of aliases that are considered to be constant
     * @param ruleSet the rule set used to simplify the {@link Value} that is passed in
     * @return a new simplified {@link Constrained} {@link Value} of {@code root}
     */
    @Nonnull
    public static Constrained<Value> simplify(@Nonnull final Value root,
                                              @Nonnull final EvaluationContext evaluationContext,
                                              @Nonnull final AliasMap aliasMap,
                                              @Nonnull final Set<CorrelationIdentifier> constantAliases,
                                              @Nonnull final AbstractValueRuleSet<Value, ValueSimplificationRuleCall> ruleSet) {
        //
        // The general strategy is to invoke the rule engine bottom up in post-fix order of the values in the value tree.
        // For each node, all rules are iteratively applied until no rules can make progress anymore. We avoid creating
        // duplicate subtrees by detecting changes made to children using object identity.
        //

        final var constraintsMap =
                Maps.<Value, QueryPlanConstraint>newLinkedHashMap();

        //
        // Use mapMaybe() to apply a lambda in post-fix bottom up fashion.
        //
        final var simplifiedValue = root.<Value>mapMaybe((current, mappedChildren) -> {

            //
            // During this mapping call we may need to recreate the current which incidentally might also be the root.
            // We need to keep track if the current was the root.
            //
            final boolean isRoot = current == root;

            //
            // If any of the children have changed as compared to the actual children of current, we need to recreate
            // current. We call computeCurrent() to do that.
            //
            current = computeCurrent(current, mappedChildren);

            //
            // Run the entire given rule set for current.
            //
            final var executionResult = executeRuleSetIteratively(isRoot ? current : root,
                    current,
                    ruleSet,
                    (rule, r, c, plannerBindings) ->
                            new ValueSimplificationRuleCall(rule, r, c, evaluationContext, plannerBindings, aliasMap,
                                    constantAliases, constraintsMap::get),
                    (results, queryPlanConstraint) ->
                            onResultsFunctionForSimplification(constraintsMap, results, queryPlanConstraint));
            Verify.verify(!executionResult.shouldReExplore());
            return executionResult.getBase();
        }).orElseThrow(() -> new RecordCoreException("expected a mapped tree"));
        return Constrained.ofConstrainedObject(simplifiedValue, collectAndComposeConstraints(simplifiedValue, constraintsMap));
    }

    @Nonnull
    private static <BASE extends TreeLike<BASE>> QueryPlanConstraint collectAndComposeConstraints(@Nonnull final BASE root,
                                                                                                  @Nonnull final Map<BASE, QueryPlanConstraint> constraintMap) {
        return root.postOrderStream()
                .flatMap(current -> {
                    final var constraint = constraintMap.get(current);
                    return Stream.ofNullable(constraint);
                })
                .filter(QueryPlanConstraint::isConstrained)
                .reduce(QueryPlanConstraint::compose)
                .orElse(QueryPlanConstraint.noConstraint());
    }

    /**
     * Main function that simplifies the given value using the {@link AbstractValueRuleSet} passed in.
     * @param current the {@link Value} to be simplified
     * @param evaluationContext the evaluation context
     * @param aliasMap an alias map of equalities
     * @param constantAliases a set of aliases that are considered to be constant
     * @param ruleSet the rule set used to simplify the {@link Value} that is passed in
     * @return a new simplified list of {@link Constrained} {@link Value}s
     */
    @Nonnull
    public static List<Constrained<Value>> simplifyCurrent(@Nonnull final Value current,
                                                           @Nonnull final EvaluationContext evaluationContext,
                                                           @Nonnull final AliasMap aliasMap,
                                                           @Nonnull final Set<CorrelationIdentifier> constantAliases,
                                                           @Nonnull final AbstractValueRuleSet<Value, ValueSimplificationRuleCall> ruleSet) {
        final var constraintsMap =
                Maps.<Value, QueryPlanConstraint>newLinkedHashMap();

        //
        // Run the entire given rule set for current.
        //
        final var executionResults =
                executeRuleSet(current,
                        current,
                        ruleSet,
                        (rule, r, c, plannerBindings) ->
                                new ValueSimplificationRuleCall(rule, r, c, evaluationContext, plannerBindings,
                                aliasMap, constantAliases, constraintsMap::get),
                        (results, queryPlanConstraint) ->
                                onResultsFunctionForSimplification(constraintsMap, results, queryPlanConstraint));
        return executionResults.stream()
                .map(ExecutionResult::getBase)
                .map(value -> Constrained.ofConstrainedObject(value,
                        collectAndComposeConstraints(value, constraintsMap)))
                .collect(ImmutableList.toImmutableList());
    }

    /**
     * Execute a set of rules on the current {@link Value}. This method assumes that all children of the current value
     * have already been simplified, that is, the rules set has already been exhaustively applied to the entire subtree
     * underneath the current value. Similar to {@link com.apple.foundationdb.record.query.plan.cascades.CascadesPlanner}
     * which creates new variations for yielded new expressions, the logic in this method applies the rule set to the
     * current value. Unlike
     * {@link #executeRuleSetIteratively(Object, Object, AbstractRuleSet, RuleCallCreator, BiFunction)} which iterates
     * starting from {code current} it attempts to exhaustively apply the rules over this {@code current}.
     * @param <RESULT> type parameter for results
     * @param <CALL> type parameter for the rule call object to be used
     * @param <BASE> type parameter this rule set matches
     * @param root the root value of the simplification/computation. This information is needed for some rules as
     *             they may only fire if {@code current} is/is not the root.
     * @param current the current value that the rule set should be executed on
     * @param ruleSet the rule set
     * @param ruleCallCreator a function that creates an instance of {@code C} which is some derivative of
     *        {@link AbstractValueRuleCall}
     * @param onResultsFunction a function that is called to manage and unwrap a computational result of a yield. This
     *                          function is trivial for simplifications.
     * @return all resulting {@link ExecutionResult}s after all rules in the rule set have been applied to {@code current}
     */
    @Nonnull
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static <RESULT, CALL extends AbstractRuleCall<RESULT, CALL, BASE>, BASE> List<ExecutionResult<BASE>> executeRuleSet(@Nonnull final BASE root,
                                                                                                                                @Nonnull BASE current,
                                                                                                                                @Nonnull final AbstractRuleSet<CALL, BASE> ruleSet,
                                                                                                                                @Nonnull final RuleCallCreator<RESULT, CALL, BASE> ruleCallCreator,
                                                                                                                                @Nonnull final BiFunction<Collection<RESULT>, QueryPlanConstraint, BASE> onResultsFunction) {
        final boolean isRoot = current == root;

        final var resultsBuilder = ImmutableList.<ExecutionResult<BASE>>builder();
        final var ruleIterator =
                ruleSet.getRules(current).iterator();

        while (ruleIterator.hasNext()) {
            final var rule = ruleIterator.next();
            final BindingMatcher<? extends BASE> matcher = rule.getMatcher();

            final var matchIterator =
                    matcher.bindMatches(RecordQueryPlannerConfiguration.defaultPlannerConfiguration(), PlannerBindings.empty(), current)
                            .iterator();

            while (matchIterator.hasNext()) {
                final var plannerBindings = matchIterator.next();
                final var ruleCall = ruleCallCreator.create(rule, isRoot ? current : root, current, plannerBindings);

                //
                // Run the rule. See if the rule yielded a simplification.
                //
                rule.onMatch(ruleCall);
                final var results = ruleCall.getResults();
                final var queryPlanConstraint = ruleCall.getResultQueryPlanConstraint();

                if (!results.isEmpty()) {
                    final var newCurrent = onResultsFunction.apply(results, queryPlanConstraint);

                    if (current != newCurrent) {
                        //
                        // We made progress.
                        //
                        resultsBuilder.add(new ExecutionResult<>(newCurrent, ruleCall.shouldReExplore()));
                    }
                }
            }
        }

        return resultsBuilder.build();
    }

    /**
     * Main function that simplifies the given value using the {@link ValueComputationRuleSet} passed in. In addition to
     * the regular {@link #simplify(Value, EvaluationContext, AliasMap, Set, AbstractValueRuleSet)}, this method uses a
     * computation rule set that is passed in to derive useful information from the value tree. In particular, this is
     * currently used to track matches of subtrees and their compensation.
     * @param <ARGUMENT> type parameter of the argument
     * @param <RESULT> type parameter of the result
     * @param root the {@link Value} to be simplified
     * @param evaluationContext the evaluation context
     * @param argument argument to the computations (of type {@code R})
     * @param aliasMap an alias map of equalities
     * @param constantAliases a set of aliases that are considered to be constant
     * @param ruleSet the computation rule set used to simplify the {@link Value} that is passed in
     * @return a new simplified {@link Pair} which contains the computation result as well as a simplified
     *         {@code root}.
     */
    @Nullable
    public static <ARGUMENT, RESULT> NonnullPair<Constrained<Value>, RESULT> compute(@Nonnull final Value root,
                                                                                     @Nonnull final EvaluationContext evaluationContext,
                                                                                     @Nonnull final ARGUMENT argument,
                                                                                     @Nonnull final AliasMap aliasMap,
                                                                                     @Nonnull final Set<CorrelationIdentifier> constantAliases,
                                                                                     @Nonnull final ValueComputationRuleSet<ARGUMENT, RESULT> ruleSet) {
        //
        // The general strategy is to invoke the rule engine bottom up in post-fix order of the values in the value tree.
        // For each node, all rules are exhaustively applied until no rules can make progress anymore. We avoid creating
        // duplicate subtrees by detecting changes made to children using object identity.
        //

        final var constraintsMap = Maps.<Value, QueryPlanConstraint>newLinkedHashMap();
        //
        // Computation results are returned by individual rules and kept in a results map. This map is heavily modified
        // by rules matching and executing on a given Value for that value.
        //
        final var resultsMap = new LinkedIdentityMap<Value, NonnullPair<Value, RESULT>>();
        final var newRoot = root.<Value>mapMaybe((current, mappedChildren) -> {
            //
            // During this mapping call we may need to recreate the current which incidentally might also be the root.
            // We need to keep track if the current was the root.
            //
            final boolean isRoot = current == root;

            //
            // If any of the children have changed as compared to the actual children of current, we need to recreate
            // current. We call computeCurrent() to do that.
            //
            current = computeCurrent(current, mappedChildren);

            //
            // Run the entire given rule set for current.
            //
            final var executionResult =
                    executeRuleSetIteratively(isRoot ? current : root,
                            current,
                            ruleSet,
                            (rule, r, c, plannerBindings) ->
                                    new ValueComputationRuleCall<>(rule, r, c, evaluationContext, argument,
                                            plannerBindings, aliasMap, constantAliases, constraintsMap::get,
                                            resultsMap::get),
                            (results, queryPlanConstraint) ->
                                    onResultsFunctionForComputation(constraintsMap, resultsMap, results,
                                            queryPlanConstraint));
            Verify.verify(!executionResult.shouldReExplore());
            return executionResult.getBase();
        }).orElseThrow(() -> new RecordCoreException("expected a mapped tree"));
        final var simplifiedPair = resultsMap.get(newRoot);
        return simplifiedPair == null ? null :
               NonnullPair.of(Constrained.ofConstrainedObject(newRoot, collectAndComposeConstraints(newRoot, constraintsMap)),
                       simplifiedPair.getRight());
    }

    /**
     * Compute a new current value if necessary, that is, if any of the children passed in are different when compared
     * to the actual children of the current value passed in.
     * @param current the current value
     * @param mappedChildren the mapped children, which may or may not be different from the actual children of the
     *                       current value
     * @return the current value that was passed in by the caller if all mapped children are identical to the actual
     *         children of the current value or a new current value that was creating by calling
     *         {@link Value#withChildren(Iterable)}
     */
    @Nonnull
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static <BASE extends TreeLike<BASE>> BASE computeCurrent(@Nonnull final BASE current, @Nonnull final Iterable<? extends BASE> mappedChildren) {
        final var children = current.getChildren();
        final var childrenIterator = children.iterator();
        final var mappedChildrenIterator = mappedChildren.iterator();
        boolean isSame = true;
        while (childrenIterator.hasNext() && mappedChildrenIterator.hasNext()) {
            final BASE child = childrenIterator.next();
            final BASE mappedChild = mappedChildrenIterator.next();
            if (child != mappedChild) {
                isSame = false;
                break;
            }
        }
        // make sure they are both exhausted or both are not exhausted
        Verify.verify(childrenIterator.hasNext() == mappedChildrenIterator.hasNext());
        return isSame ? current : current.withChildren(mappedChildren);
    }

    @Nonnull
    private static <BASE> BASE onResultsFunctionForSimplification(@Nonnull final Map<BASE, QueryPlanConstraint> constrainstsMap,
                                                                  @Nonnull final Collection<BASE> results,
                                                                  @Nonnull final QueryPlanConstraint queryPlanConstraint) {
        Verify.verify(results.size() <= 1);

        final var result = Iterables.getOnlyElement(results);
        constrainstsMap.put(result, queryPlanConstraint);
        return result;
    }

    @Nonnull
    private static <BASE, R> BASE onResultsFunctionForComputation(@Nonnull final Map<BASE, QueryPlanConstraint> constrainstsMap,
                                                                  @Nonnull final Map<BASE, NonnullPair<BASE, R>> resultsMap,
                                                                  @Nonnull final Collection<NonnullPair<BASE, R>> results,
                                                                  @Nonnull final QueryPlanConstraint queryPlanConstraint) {
        Verify.verify(results.size() <= 1);

        final var resultPair = Iterables.getOnlyElement(results);
        final var value = resultPair.getLeft();
        constrainstsMap.put(value, queryPlanConstraint);
        resultsMap.put(value, resultPair);
        return value;
    }

    /**
     * Execute a set of rules on the current {@link Value}. This method assumes that all children of the current value
     * have already been simplified, that is, the rules set has already been exhaustively applied to the entire subtree
     * underneath the current value. In contrast to {@link com.apple.foundationdb.record.query.plan.cascades.CascadesPlanner}
     * which creates new variations for yielded new expressions, the logic in this method applies the rule set in a
     * destructive manner meaning that the last yield wins and all previous yields on the current values were merely
     * interim stepping stones in transforming the original value to the final value. Thus, the order of the rules in
     * the rule set is important.
     * @param <RESULT> type parameter for results
     * @param <CALL> type parameter for the rule call object to be used
     * @param <BASE> type parameter this rule set matches
     * @param root the root value of the simplification/computation. This information is needed for some rules as
     *             they may only fire if {@code current} is/is not the root.
     * @param current the current value that the rule set should be executed on
     * @param ruleSet the rule set
     * @param ruleCallCreator a function that creates an instance of {@code C} which is some derivative of
     *        {@link AbstractValueRuleCall}
     * @param onResultsFunction a function that is called to manage and unwrap a computational result of a yield. This
     *                          function is trivial for simplifications.
     * @return a resulting {@link Value} after all rules in the rule set have been exhaustively applied
     */
    @Nonnull
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static <RESULT, CALL extends AbstractRuleCall<RESULT, CALL, BASE>, BASE> ExecutionResult<BASE> executeRuleSetIteratively(@Nonnull final BASE root,
                                                                                                                                     @Nonnull BASE current,
                                                                                                                                     @Nonnull final AbstractRuleSet<CALL, BASE> ruleSet,
                                                                                                                                     @Nonnull final RuleCallCreator<RESULT, CALL, BASE> ruleCallCreator,
                                                                                                                                     @Nonnull final BiFunction<Collection<RESULT>, QueryPlanConstraint, BASE> onResultsFunction) {
        final boolean isRoot = current == root;
        BASE newCurrent = current;
        do {
            current = newCurrent;
            final var ruleIterator =
                    ruleSet.getRules(current).iterator();

            while (ruleIterator.hasNext()) {
                final var rule = ruleIterator.next();
                final BindingMatcher<? extends BASE> matcher = rule.getMatcher();

                final var matchIterator =
                        matcher.bindMatches(RecordQueryPlannerConfiguration.defaultPlannerConfiguration(), PlannerBindings.empty(), current)
                                .iterator();

                while (matchIterator.hasNext()) {
                    final var plannerBindings = matchIterator.next();
                    final var ruleCall = ruleCallCreator.create(rule, isRoot ? current : root, current, plannerBindings);

                    //
                    // Run the rule. See if the rule yielded a simplification.
                    //
                    rule.onMatch(ruleCall);
                    final var results = ruleCall.getResults();
                    final var queryPlanConstraint = ruleCall.getResultQueryPlanConstraint();

                    if (!results.isEmpty()) {
                        newCurrent = onResultsFunction.apply(results, queryPlanConstraint);

                        if (current != newCurrent) {
                            //
                            // We made progress.
                            //
                            if (ruleCall.shouldReExplore()) {
                                //
                                // If the ruleCall indicated that the new base needs to be re-explored we are done here.
                                //
                                return new ExecutionResult<>(newCurrent, true);
                            }

                            //
                            // Make sure we exit the inner while loops and restart with the first rule
                            // for the new `current` again.
                            //
                            break;
                        }
                    }
                }

                if (current != newCurrent) {
                    break;
                }
            }
        } while (current != newCurrent);

        return new ExecutionResult<>(current, false);
    }

    /**
     * Main function that simplifies the given value using the {@link QueryPredicateComputationRuleSet} passed in.
     * @param root the {@link Value} to be simplified
     * @param evaluationContext the evaluation context
     * @param aliasMap an alias map of equalities
     * @param constantAliases a set of aliases that are considered to be constant
     * @param ruleSet the computation rule set used to simplify the {@link Value} that is passed in
     * @return a new simplified {@link Constrained} {@link QueryPredicate}
     */
    @Nonnull
    public static Constrained<QueryPredicate> optimize(@Nonnull final QueryPredicate root,
                                                       @Nonnull final EvaluationContext evaluationContext,
                                                       @Nonnull final AliasMap aliasMap,
                                                       @Nonnull final Set<CorrelationIdentifier> constantAliases,
                                                       @Nonnull final AbstractRuleSet<QueryPredicateSimplificationRuleCall, QueryPredicate> ruleSet) {
        final var constraintsMap = Maps.<QueryPredicate, QueryPlanConstraint>newLinkedHashMap();
        final var simplifiedPredicate =
                simplifyWithReExploration(root,
                        root,
                        constraintsMap,
                        ruleSet,
                        (rule, r, c, plannerBindings) ->
                                new QueryPredicateSimplificationRuleCall(rule, r, c, evaluationContext, plannerBindings,
                                        aliasMap, constantAliases, constraintsMap::get));
        return simplifiedPredicate == root
               ? Constrained.unconstrained(root)
               : Constrained.ofConstrainedObject(simplifiedPredicate,
                collectAndComposeConstraints(simplifiedPredicate, constraintsMap));
    }

    /**
     * This method simplifies the tree like and additionally allows the rule engine to re-explore the children of a
     * newly yielded {@code BASE}.
     * @param <CALL> type parameter for the rule call object to be used
     * @param <BASE> type parameter this rule set matches
     * @param root the root value of the simplification/computation. This information is needed for some rules as
     *             they may only fire if {@code current} is/is not the root.
     * @param ruleSet the rule set
     * @param ruleCallCreator a function that creates an instance of type {@code CALL} which is some derivative of
     *        {@link AbstractValueRuleCall}.
     * @return a resulting {@link Value} after all rules in the rule set have been exhaustively applied
     */
    @Nonnull
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static <CALL extends AbstractRuleCall<BASE, CALL, BASE>, BASE extends TreeLike<BASE>> BASE simplifyWithReExploration(@Nonnull final BASE root,
                                                                                                                                 @Nonnull BASE current,
                                                                                                                                 @Nonnull final Map<BASE, QueryPlanConstraint> constraintsMap,
                                                                                                                                 @Nonnull final AbstractRuleSet<CALL, BASE> ruleSet,
                                                                                                                                 @Nonnull final RuleCallCreator<BASE, CALL, BASE> ruleCallCreator) {
        final var isRoot = root == current;
        ExecutionResult<BASE> executionResult;
        do {
            final var simplifiedChildren = Lists.<BASE>newArrayList();
            for (final var child : current.getChildren()) {
                simplifiedChildren.add(simplifyWithReExploration(isRoot ? current : root, child, constraintsMap, ruleSet, ruleCallCreator));
            }

            current = computeCurrent(current, simplifiedChildren);

            executionResult =
                    executeRuleSetIteratively(isRoot ? current : root,
                            current,
                            ruleSet,
                            ruleCallCreator,
                            (results, queryPlanConstraint) ->
                                    onResultsFunctionForSimplification(constraintsMap, results, queryPlanConstraint));

            final var newCurrent = executionResult.getBase();
            Verify.verify(newCurrent != current || !executionResult.shouldReExplore());
            current = newCurrent;
        } while (executionResult.shouldReExplore());

        return current;
    }

    private static class ExecutionResult<BASE> {
        @Nonnull
        private final BASE base;

        private final boolean shouldReExplore;

        public ExecutionResult(@Nonnull final BASE base, final boolean shouldReExplore) {
            this.base = base;
            this.shouldReExplore = shouldReExplore;
        }

        @Nonnull
        public BASE getBase() {
            return base;
        }

        public boolean shouldReExplore() {
            return shouldReExplore;
        }
    }

    /**
     * Functional interface to create a specific rule call object.
     * @param <RESULT> the type parameter representing the type of result that yielded
     * @param <CALL> the type parameter extending {@link AbstractValueRuleCall}
     * @param <BASE> the type of entity the rule matches
     */
    @FunctionalInterface
    public interface RuleCallCreator<RESULT, CALL extends AbstractRuleCall<RESULT, CALL, BASE>, BASE> {
        CALL create(@Nonnull PlannerRule<CALL, ? extends BASE> rule,
                    @Nonnull BASE root,
                    @Nonnull BASE current,
                    @Nonnull PlannerBindings plannerBindings);
    }
}
