/*
 * BooleanValue.java
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

package com.apple.foundationdb.record.query.plan.cascades.values;

import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.apple.foundationdb.record.query.plan.cascades.predicates.QueryPredicate;
import com.apple.foundationdb.record.query.plan.cascades.typing.TypeRepository;
import com.google.common.collect.ImmutableSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.Set;

/**
 * Shim class to translate objects of type {@link Value} to {@link QueryPredicate}.
 */
public interface BooleanValue extends Value {
    @Nonnull
    @Override
    default Type getResultType() {
        return Type.primitiveType(Type.TypeCode.BOOLEAN);
    }

    /**
     * Translates the {@link BooleanValue} into a {@link QueryPredicate}.
     *
     * @param typeRepository a type repository that can be passed to e.g. compile-time evaluable functions
     * @param localAliases set of aliases which are immediately visible to the expression.
     * @return A {@link QueryPredicate} that is equivalent to this {@link BooleanValue} expression.
     */
    Optional<QueryPredicate> toQueryPredicate(@Nullable TypeRepository typeRepository,
                                              @Nonnull Set<CorrelationIdentifier> localAliases);

    /**
     * Translates the {@link BooleanValue} into a {@link QueryPredicate}.
     *
     * @param typeRepository a type repository that can be passed to e.g. compile-time evaluable functions
     * @param localAlias the alias that is immediately visible to the expression.
     * @return A {@link QueryPredicate} that is equivalent to this {@link BooleanValue} expression.
     */
    default Optional<QueryPredicate> toQueryPredicate(@Nullable TypeRepository typeRepository,
                                              @Nonnull CorrelationIdentifier localAlias) {
        return toQueryPredicate(typeRepository, ImmutableSet.of(localAlias));
    }
}
