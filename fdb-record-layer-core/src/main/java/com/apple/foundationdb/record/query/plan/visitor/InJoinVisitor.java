/*
 * InJoinVisitor.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2020 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.query.plan.visitor;

import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.query.plan.PlannableIndexTypes;
import com.apple.foundationdb.record.query.plan.cascades.Reference;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryFetchFromPartialRecordPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryInJoinPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.apple.foundationdb.record.query.plan.plans.TranslateValueFunction;
import com.google.common.collect.Sets;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A substitution visitor that pushes a {@link RecordQueryInJoinPlan} below a {@link RecordQueryFetchFromPartialRecordPlan}.
 */
public class InJoinVisitor extends RecordQueryPlannerSubstitutionVisitor {
    public InJoinVisitor(@Nonnull final RecordMetaData recordMetadata, @Nonnull final PlannableIndexTypes indexTypes, @Nullable final KeyExpression commonPrimaryKey) {
        super(recordMetadata, indexTypes, commonPrimaryKey);
    }

    @Nonnull
    @Override
    public RecordQueryPlan postVisit(@Nonnull RecordQueryPlan recordQueryPlan) {
        if (recordQueryPlan instanceof RecordQueryInJoinPlan) {
            final RecordQueryInJoinPlan inJoinPlan = (RecordQueryInJoinPlan)recordQueryPlan;

            @Nullable RecordQueryFetchFromPartialRecordPlan.FetchIndexRecords fetchIndexRecords = resolveFetchIndexRecordsFromPlan(inJoinPlan.getChild());
            if (fetchIndexRecords == null) {
                return recordQueryPlan;
            }

            @Nullable RecordQueryPlan removedFetchPlan = removeIndexFetch(inJoinPlan.getChild(), Sets.newHashSet());
            if (removedFetchPlan == null) {
                return recordQueryPlan;
            }

            recordQueryPlan = new RecordQueryFetchFromPartialRecordPlan(
                    inJoinPlan.withChild(Reference.plannedOf(removedFetchPlan)),
                    TranslateValueFunction.unableToTranslate(),
                    new Type.Any(),
                    fetchIndexRecords);
        }
        return recordQueryPlan;
    }
}
