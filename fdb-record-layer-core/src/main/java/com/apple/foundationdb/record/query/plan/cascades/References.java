/*
 * References.java
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

package com.apple.foundationdb.record.query.plan.cascades;

import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.query.combinatorics.TopologicalSort;
import com.apple.foundationdb.record.query.plan.cascades.debug.Debugger;
import com.apple.foundationdb.record.query.plan.cascades.expressions.RelationalExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.RelationalExpressionWithChildren;
import com.apple.foundationdb.record.query.plan.cascades.values.translation.TranslationMap;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.apple.foundationdb.record.query.plan.cascades.properties.ReferencesAndDependenciesProperty.referencesAndDependencies;

/**
 * Utility methods for {@link Reference}s.
 */
public class References {
    private References() {
        // do not instantiate
    }

    // TODO rebase() on quantifiers, expressions, and references should be deprecated as none of them take memoization
    //  into account. They can but they don't currently. These translateCorrelations() calls should be made explicit in
    //  a way that the caller must explicitly pass a memoizer. Nobody calls this code path currently. Let's keep it this
    //  way until we can do it properly.
    public static List<? extends Reference> translateCorrelations(@Nonnull final List<? extends Reference> refs,
                                                                  @Nonnull final TranslationMap translationMap,
                                                                  final boolean shouldSimplifyValues) {
        if (refs.isEmpty()) {
            return ImmutableList.of();
        }

        final var partialOrder = referencesAndDependencies().evaluate(refs);

        final var references =
                TopologicalSort.anyTopologicalOrderPermutation(partialOrder)
                        .orElseThrow(() -> new RecordCoreException("graph has cycles"));

        final var cachedTranslationsMap =
                Maps.<Reference, Reference>newIdentityHashMap();

        for (final var reference : references) {
            if (reference.getCorrelatedTo().stream().anyMatch(translationMap::containsSourceAlias)) {
                var allMembersSame = true;
                final var translatedExploratoryExpressionsBuilder = ImmutableList.<RelationalExpression>builder();
                final var translatedFinalExpressionsBuilder = ImmutableList.<RelationalExpression>builder();
                for (final var expression : reference.getAllMemberExpressions()) {
                    var allChildTranslationsSame = true;
                    final var translatedQuantifiersBuilder = ImmutableList.<Quantifier>builder();
                    for (final var quantifier : expression.getQuantifiers()) {
                        final var childReference = quantifier.getRangesOver();

                        // these must exist
                        Verify.verify(cachedTranslationsMap.containsKey(childReference));
                        final Reference translatedChildReference = cachedTranslationsMap.get(childReference);
                        if (translatedChildReference != childReference) {
                            translatedQuantifiersBuilder.add(quantifier.overNewReference(translatedChildReference));
                            allChildTranslationsSame = false;
                        } else {
                            translatedQuantifiersBuilder.add(quantifier);
                        }
                    }

                    final var translatedQuantifiers = translatedQuantifiersBuilder.build();
                    final RelationalExpression translatedExpression;

                    // we may not have to translate the current member
                    if (allChildTranslationsSame) {
                        final Set<CorrelationIdentifier> memberCorrelatedTo;
                        if (expression instanceof RelationalExpressionWithChildren) {
                            memberCorrelatedTo = ((RelationalExpressionWithChildren)expression).getCorrelatedToWithoutChildren();
                        } else {
                            memberCorrelatedTo = expression.getCorrelatedTo();
                        }

                        if (memberCorrelatedTo.stream().noneMatch(translationMap::containsSourceAlias)) {
                            translatedExpression = expression;
                        } else {
                            translatedExpression = expression.translateCorrelations(translationMap, shouldSimplifyValues,
                                    translatedQuantifiers);
                            Debugger.withDebugger(debugger -> debugger.onEvent(
                                    new Debugger.TranslateCorrelationsEvent(translatedExpression, Debugger.Location.COUNT)));
                            allMembersSame = false;
                        }
                    } else {
                        translatedExpression = expression.translateCorrelations(translationMap, shouldSimplifyValues,
                                translatedQuantifiers);
                        Debugger.withDebugger(debugger -> debugger.onEvent(
                                new Debugger.TranslateCorrelationsEvent(translatedExpression, Debugger.Location.COUNT)));
                        allMembersSame = false;
                    }
                    if (reference.isFinal(expression)) {
                        translatedFinalExpressionsBuilder.add(translatedExpression);
                    }
                    if (reference.isExploratory(expression)) {
                        translatedExploratoryExpressionsBuilder.add(translatedExpression);
                    }
                }
                if (allMembersSame) {
                    cachedTranslationsMap.put(reference, reference);
                } else {
                    cachedTranslationsMap.put(reference, Reference.of(reference.getPlannerStage(),
                            translatedExploratoryExpressionsBuilder.build(),
                            translatedFinalExpressionsBuilder.build()));
                }
            } else {
                cachedTranslationsMap.put(reference, reference);
            }
        }

        return refs.stream()
                .map(ref -> Objects.requireNonNull(cachedTranslationsMap.get(ref)))
                .collect(ImmutableList.toImmutableList());
    }
}
