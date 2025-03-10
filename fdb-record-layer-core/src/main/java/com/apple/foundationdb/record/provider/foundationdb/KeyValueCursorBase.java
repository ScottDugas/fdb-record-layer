/*
 * KeyValueCursor.java
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

package com.apple.foundationdb.record.provider.foundationdb;

import com.apple.foundationdb.KeySelector;
import com.apple.foundationdb.KeyValue;
import com.apple.foundationdb.Range;
import com.apple.foundationdb.ReadTransaction;
import com.apple.foundationdb.StreamingMode;
import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.annotation.SpotBugsSuppressWarnings;
import com.apple.foundationdb.async.AsyncIterator;
import com.apple.foundationdb.record.CursorStreamingMode;
import com.apple.foundationdb.record.EndpointType;
import com.apple.foundationdb.record.KeyRange;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCursorContinuation;
import com.apple.foundationdb.record.RecordCursorResult;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.TupleRange;
import com.apple.foundationdb.record.cursors.AsyncIteratorCursor;
import com.apple.foundationdb.record.cursors.BaseCursor;
import com.apple.foundationdb.record.cursors.CursorLimitManager;
import com.apple.foundationdb.subspace.Subspace;
import com.apple.foundationdb.tuple.Tuple;
import com.google.protobuf.ByteString;
import com.google.protobuf.ZeroCopyByteString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The base class for cursors scanning ranges of the FDB database.
 * @param <K> the type of the KeyValue that this cursor iterates over
 */
@API(API.Status.UNSTABLE)
public abstract class KeyValueCursorBase<K extends KeyValue> extends AsyncIteratorCursor<K> implements BaseCursor<K> {
    @Nonnull
    private final FDBRecordContext context;
    private final int prefixLength;
    @Nonnull
    private final CursorLimitManager limitManager;
    private int valuesLimit;
    // the pointer may be mutated, but the actual array must never be mutated or continuations will break
    @Nullable
    private byte[] lastKey;

    protected KeyValueCursorBase(@Nonnull final FDBRecordContext context,
                                 @Nonnull final AsyncIterator<K> iterator,
                                 int prefixLength,
                                 @Nonnull final CursorLimitManager limitManager,
                                 int valuesLimit) {
        super(context.getExecutor(), iterator);

        this.context = context;
        this.prefixLength = prefixLength;
        this.limitManager = limitManager;
        this.valuesLimit = valuesLimit;

        context.instrument(FDBStoreTimer.DetailEvents.GET_SCAN_RANGE_RAW_FIRST_CHUNK, iterator.onHasNext());
    }

    @Nonnull
    @Override
    public CompletableFuture<RecordCursorResult<K>> onNext() {
        if (nextResult != null && !nextResult.hasNext()) {
            // This guard is needed to guarantee that if onNext is called multiple times after the cursor has
            // returned a result without a value, then the same NoNextReason is returned each time. Without this guard,
            // one might return SCAN_LIMIT_REACHED (for example) after returning a result with SOURCE_EXHAUSTED because
            // of the tryRecordScan check.
            return CompletableFuture.completedFuture(nextResult);
        } else if (limitManager.tryRecordScan()) {
            return iterator.onHasNext().thenApply(hasNext -> {
                if (hasNext) {
                    K kv = iterator.next();
                    if (context != null) {
                        context.increment(FDBStoreTimer.Counts.LOAD_SCAN_ENTRY);
                        context.increment(FDBStoreTimer.Counts.LOAD_KEY_VALUE);
                    }
                    limitManager.reportScannedBytes((long)kv.getKey().length + (long)kv.getValue().length);
                    // Note that this mutates the pointer and NOT the array.
                    // If the value of lastKey is mutated, the Continuation class will break.
                    lastKey = kv.getKey();
                    valuesSeen++;
                    nextResult = RecordCursorResult.withNextValue(kv, continuationHelper());
                } else if (valuesSeen >= valuesLimit) {
                    // Source iterator hit limit that we passed down.
                    nextResult = RecordCursorResult.withoutNextValue(continuationHelper(), NoNextReason.RETURN_LIMIT_REACHED);
                } else {
                    // Source iterator is exhausted.
                    nextResult = RecordCursorResult.exhausted();
                }
                return nextResult;
            });
        } else { // a limit must have been exceeded
            final Optional<NoNextReason> stoppedReason = limitManager.getStoppedReason();
            if (!stoppedReason.isPresent()) {
                throw new RecordCoreException("limit manager stopped KeyValueCursor but did not report a reason");
            }
            nextResult = RecordCursorResult.withoutNextValue(continuationHelper(), stoppedReason.get());
            return CompletableFuture.completedFuture(nextResult);
        }
    }

    @Override
    @Nonnull
    public RecordCursorResult<K> getNext() {
        return context.asyncToSync(FDBStoreTimer.Waits.WAIT_ADVANCE_CURSOR, onNext());
    }

    @Nonnull
    private RecordCursorContinuation continuationHelper() {
        return new Continuation(lastKey, prefixLength);
    }

    private static class Continuation implements RecordCursorContinuation {
        @Nullable
        private final byte[] lastKey;
        private final int prefixLength;

        public Continuation(@Nullable final byte[] lastKey, final int prefixLength) {
            // Note that doing this without a full copy is dangerous if the array is ever mutated.
            // Currently, this never happens and the only thing that changes is which array lastKey points to.
            // However, if logic in KeyValueCursor or KeyValue changes, this could break continuations.
            // To resolve it, we could resort to doing a full copy here, although that's somewhat expensive.
            this.lastKey = lastKey;
            this.prefixLength = prefixLength;
        }

        @Override
        public boolean isEnd() {
            return lastKey == null;
        }

        @Nonnull
        @Override
        public ByteString toByteString() {
            if (lastKey == null) {
                return ByteString.EMPTY;
            }
            ByteString base = ZeroCopyByteString.wrap(lastKey);
            return base.substring(prefixLength, lastKey.length);
        }

        @Nullable
        @Override
        public byte[] toBytes() {
            if (lastKey == null) {
                return null;
            }
            return Arrays.copyOfRange(lastKey, prefixLength, lastKey.length);
        }
    }

    /**
     * A builder for {@link KeyValueCursorBase}.
     * @param <T> the type of the concrete subclass of the builder
     * This follows a pattern for nested builder class, hence is generic, so that subclasses can pass in their type (and
     * implement {@link #self()}) to return the subclass builder correct type.
     *
     * <pre><code>
     * KeyValueCursorSubclass.Builder.withSubspace(subspace)
     *                     .setContext(context)
     *                     .setRange(TupleRange.ALL)
     *                     .setContinuation(null)
     *                     .setScanProperties(ScanProperties.FORWARD_SCAN)
     *                     .build()
     * </code></pre>
     */
    @API(API.Status.UNSTABLE)
    public abstract static class Builder<T extends Builder<T>> {

        private int prefixLength;
        private FDBRecordContext context = null;
        private final Subspace subspace;
        private byte[] continuation = null;
        private ScanProperties scanProperties = null;
        private byte[] lowBytes = null;
        private byte[] highBytes = null;
        private EndpointType lowEndpoint = null;
        private EndpointType highEndpoint = null;
        private ReadTransaction transaction;
        private CursorLimitManager limitManager;
        private int valuesLimit;
        private int limit;
        private boolean reverse;
        private StreamingMode streamingMode;
        private KeySelector begin;
        private KeySelector end;

        protected Builder(@Nonnull Subspace subspace) {
            this.subspace = subspace;
        }

        /**
         * Called by subclasses to perform chacks and initialize all the required properties needed to construct cursors.
         */
        protected void prepare() {
            if (subspace == null) {
                throw new RecordCoreException("record subspace must be supplied");
            }

            if (context == null) {
                throw new RecordCoreException("record context must be supplied");
            }

            if (scanProperties == null) {
                throw new RecordCoreException("record scanProperties must be supplied");
            }

            if (lowBytes == null) {
                lowBytes = subspace.pack();
            }
            if (highBytes == null) {
                highBytes = subspace.pack();
            }
            if (lowEndpoint == null) {
                lowEndpoint = EndpointType.TREE_START;
            }
            if (highEndpoint == null) {
                highEndpoint = EndpointType.TREE_END;
            }

            // Handle the continuation and then turn the endpoints into one byte array on the
            // left (inclusive) and another on the right (exclusive).
            prefixLength = calculatePrefixLength();

            reverse = scanProperties.isReverse();
            if (continuation != null) {
                final byte[] continuationBytes = new byte[prefixLength + continuation.length];
                System.arraycopy(lowBytes, 0, continuationBytes, 0, prefixLength);
                System.arraycopy(continuation, 0, continuationBytes, prefixLength, continuation.length);
                if (reverse) {
                    highBytes = continuationBytes;
                    highEndpoint = EndpointType.CONTINUATION;
                } else {
                    lowBytes = continuationBytes;
                    lowEndpoint = EndpointType.CONTINUATION;
                }
            }
            final Range byteRange = TupleRange.toRange(lowBytes, highBytes, lowEndpoint, highEndpoint);
            lowBytes = byteRange.begin;
            highBytes = byteRange.end;

            // Begin the scan with the new arrays
            begin = KeySelector.firstGreaterOrEqual(lowBytes);
            end = KeySelector.firstGreaterOrEqual(highBytes);
            if (scanProperties.getExecuteProperties().getSkip() > 0) {
                if (reverse) {
                    end = end.add(- scanProperties.getExecuteProperties().getSkip());
                } else {
                    begin = begin.add(scanProperties.getExecuteProperties().getSkip());
                }
            }

            limit = scanProperties.getExecuteProperties().getReturnedRowLimit();
            streamingMode = calcStreamingMode(scanProperties.getCursorStreamingMode(), limit);

            transaction = context.readTransaction(scanProperties.getExecuteProperties().getIsolationLevel().isSnapshot());
            limitManager = new CursorLimitManager(context, scanProperties);
            valuesLimit = scanProperties.getExecuteProperties().getReturnedRowLimitOrMax();
        }

        public T setContext(FDBRecordContext context) {
            this.context = context;
            return self();
        }

        @SpotBugsSuppressWarnings(value = "EI2", justification = "copies are expensive")
        public T setContinuation(@Nullable byte[] continuation) {
            this.continuation = continuation;
            return self();
        }

        public T setScanProperties(@Nonnull ScanProperties scanProperties) {
            this.scanProperties = scanProperties;
            return self();
        }

        public T setRange(@Nonnull KeyRange range) {
            setLow(range.getLowKey(), range.getLowEndpoint());
            setHigh(range.getHighKey(), range.getHighEndpoint());
            return self();
        }

        public T setRange(@Nonnull TupleRange range) {
            setLow(range.getLow(), range.getLowEndpoint());
            setHigh(range.getHigh(), range.getHighEndpoint());
            return self();
        }

        public T setLow(@Nullable Tuple low, @Nonnull EndpointType lowEndpoint) {
            setLow(low != null ? subspace.pack(low) : subspace.pack(), lowEndpoint);
            return self();
        }

        @SpotBugsSuppressWarnings(value = "EI2", justification = "copies are expensive")
        public T setLow(@Nonnull byte[] lowBytes, @Nonnull EndpointType lowEndpoint) {
            this.lowBytes = lowBytes;
            this.lowEndpoint = lowEndpoint;
            return self();
        }

        public T setHigh(@Nullable Tuple high, @Nonnull EndpointType highEndpoint) {
            setHigh(high != null ? subspace.pack(high) : subspace.pack(), highEndpoint);
            return self();
        }

        @SpotBugsSuppressWarnings(value = "EI2", justification = "copies are expensive")
        public T setHigh(@Nonnull byte[] highBytes, @Nonnull EndpointType highEndpoint) {
            this.highBytes = highBytes;
            this.highEndpoint = highEndpoint;
            return self();
        }

        /**
         * Calculate the key prefix length for the returned values. This will be used to derive the primary key used in
         * the calculated continuation.
         * @return the length of the key prefix
         */
        protected int calculatePrefixLength() {
            int prefixLength = subspace.pack().length;
            while ((prefixLength < lowBytes.length) &&
                   (prefixLength < highBytes.length) &&
                   (lowBytes[prefixLength] == highBytes[prefixLength])) {
                prefixLength++;
            }
            return prefixLength;
        }

        public FDBRecordContext getContext() {
            return context;
        }

        public int getLimit() {
            return limit;
        }

        public int getPrefixLength() {
            return prefixLength;
        }

        public ReadTransaction getTransaction() {
            return transaction;
        }

        public CursorLimitManager getLimitManager() {
            return limitManager;
        }

        public int getValuesLimit() {
            return valuesLimit;
        }

        public boolean isReverse() {
            return reverse;
        }

        public StreamingMode getStreamingMode() {
            return streamingMode;
        }

        public KeySelector getBegin() {
            return begin;
        }

        public KeySelector getEnd() {
            return end;
        }

        private StreamingMode calcStreamingMode(final CursorStreamingMode propertiesStreamingMode, final int limit) {
            if (propertiesStreamingMode == CursorStreamingMode.ITERATOR) {
                return StreamingMode.ITERATOR;
            } else if (propertiesStreamingMode == CursorStreamingMode.LARGE) {
                return StreamingMode.LARGE;
            } else if (propertiesStreamingMode == CursorStreamingMode.MEDIUM) {
                return StreamingMode.MEDIUM;
            } else if (propertiesStreamingMode == CursorStreamingMode.SMALL) {
                return StreamingMode.SMALL;
            } else if (limit == ReadTransaction.ROW_LIMIT_UNLIMITED) {
                return StreamingMode.WANT_ALL;
            } else {
                return StreamingMode.EXACT;
            }
        }

        /**
         * {@code Self} pattern is used to return the appropriate THIS for the builder {@code set*} methods. In order to
         * be able to string set methods together where some are implemented by a superclass, the self() method returns
         * this with the subclass type.
         * @return this object from the subclass
         */
        protected abstract T self();
    }
}
