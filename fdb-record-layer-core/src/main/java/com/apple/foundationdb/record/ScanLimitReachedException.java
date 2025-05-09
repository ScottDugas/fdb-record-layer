/*
 * ScanLimitReachedException.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2018 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.cursors.CursorLimitManager;

import javax.annotation.Nonnull;

/**
 * Exception thrown when a transaction tries to scan more than the allowed number of key-value pairs.
 * @see RecordCursor.NoNextReason#SCAN_LIMIT_REACHED
 * @see ExecuteProperties.Builder#setFailOnScanLimitReached(boolean)
 * @see CursorLimitManager#tryRecordScan()
 */
@API(API.Status.UNSTABLE)
@SuppressWarnings("serial")
public class ScanLimitReachedException extends RecordCoreException {
    public ScanLimitReachedException(@Nonnull String msg) {
        super(msg);
    }
}
