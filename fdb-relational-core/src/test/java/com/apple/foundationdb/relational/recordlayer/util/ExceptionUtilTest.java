/*
 * ExceptionUtilTest.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2021-2024 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.relational.recordlayer.util;

import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCoreStorageException;
import com.apple.foundationdb.relational.api.exceptions.ErrorCode;
import com.apple.foundationdb.relational.api.exceptions.RelationalException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

class ExceptionUtilTest {

    @Test
    @SuppressWarnings("ThrowableNotThrown") //intentional
    void detectsTransactionIsNoLongerActive() throws Exception {
        RelationalException converted = ExceptionUtil.toRelationalException(new RecordCoreStorageException("Transaction is no longer active"));
        Assertions.assertEquals(ErrorCode.TRANSACTION_INACTIVE, converted.getErrorCode());
    }

    @Test
    @SuppressWarnings("ThrowableNotThrown") //intentional
    void carriesRecordLayerContext() {
        RecordCoreException rece = new RecordCoreException("this is a test error message").addLogInfo("table", "foo");
        RelationalException converted = ExceptionUtil.toRelationalException(rece);
        Assertions.assertEquals(ErrorCode.UNKNOWN, converted.getErrorCode(), "Incorrect error code!");
        Map<String, Object> ctxMap = converted.getContext();
        Assertions.assertTrue(ctxMap.containsKey("table"), "context missing key!");
        Assertions.assertEquals(ctxMap.get("table"), "foo", "Incorrect context value!");
    }
}
