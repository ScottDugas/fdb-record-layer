/*
 * ValueComputationRule.java
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

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.BindingMatcher;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;
import com.apple.foundationdb.record.util.pair.NonnullPair;
import com.google.common.collect.Streams;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Intermediate class that fixes the type of the {@link com.apple.foundationdb.record.query.plan.cascades.PlannerRuleCall}.
 * @param <A> the type of object that functions as the argument to this rule
 * @param <R> the type of object that this rule produces as result
 * @param <T> the type of object that this rule helps simplify
 */
@API(API.Status.EXPERIMENTAL)
public abstract class ValueComputationRule<A, R, T extends Value> extends AbstractValueRule<NonnullPair<Value, R>, ValueComputationRuleCall<A, R>, T> {
    public ValueComputationRule(@Nonnull final BindingMatcher<T> matcher) {
        super(matcher);
    }

    /**
     * A helper method that transforms a simplification rule into a computation rule that allows the caller to reuse
     * simplification rules in more complicated computation use cases. In order to compensate for the lack of a result
     * of a computation which the implementation of a simplification rule does not have, the caller can pass in a
     * {@link OnMatchComputationFunction} that is invoked for each yielded result.
     * @param simplificationRule the original simplification rule
     * @param onMatchComputationFunction the function that computes the result
     * @param <A> the type parameter for the arguments
     * @param <R> the type parameter for the result of the computation
     * @param <T> the type parameter the rule matches
     * @return a new computation rule that can be used in an appropriate compatible computation rule set
     */
    @Nonnull
    static <A, R, T extends Value> ValueComputationRule<A, R, T> fromSimplificationRule(@Nonnull final ValueSimplificationRule<T> simplificationRule,
                                                                                        @Nonnull final OnMatchComputationFunction<A, R> onMatchComputationFunction) {
        return new ValueComputationRule<>(simplificationRule.getMatcher()) {
            @Nonnull
            @Override
            public Optional<Class<?>> getRootOperator() {
                return simplificationRule.getRootOperator();
            }

            @Override
            public void onMatch(@Nonnull final ValueComputationRuleCall<A, R> call) {
                final var childrenResults =
                        Streams.stream(call.getCurrent()
                                        .getChildren())
                                .map(value -> {
                                    final var childResultPair = call.getResult(value);
                                    return childResultPair == null ? (R)null : childResultPair.getValue();
                                })
                                .collect(Collectors.toList());
                final var simplificationRuleCall =
                        call.toValueSimplificationRuleCall(simplificationRule);
                simplificationRule.onMatch(simplificationRuleCall);
                final var results = simplificationRuleCall.getResults();
                results.forEach(resultValue -> call.yieldValue(resultValue,
                        onMatchComputationFunction.apply(call.getArgument(), resultValue, childrenResults)));
                call.composeAdditionalConstraint(simplificationRuleCall.getResultQueryPlanConstraint());
            }
        };
    }

    /**
     * Functional interface whose {@code apply} method is invoked <em>after</em> using the simplification's results.
     * @param <A> the argument type
     * @param <R> the result type
     */
    @FunctionalInterface
    public interface OnMatchComputationFunction<A, R> {
        R apply(@Nullable A argument, @Nonnull Value value, @Nonnull List<R> childrenResults);
    }
}
