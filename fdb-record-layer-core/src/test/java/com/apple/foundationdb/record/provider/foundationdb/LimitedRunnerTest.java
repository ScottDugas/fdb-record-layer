/*
 * LimitedRunnerTest.java
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

package com.apple.foundationdb.record.provider.foundationdb;

import com.apple.foundationdb.FDB;
import com.apple.foundationdb.FDBError;
import com.apple.foundationdb.FDBException;
import com.apple.foundationdb.async.AsyncUtil;
import com.apple.foundationdb.record.TestHelpers;
import com.apple.foundationdb.record.provider.foundationdb.runners.ExponentialDelay;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// if any of these tests take longer than 2 seconds, it almost certainly indicates a bug resulting in the future
// never completing
@Timeout(value = 2, unit = TimeUnit.SECONDS)
class LimitedRunnerTest {

    public static final String RETRIABLE_NON_LESSEN_WORK_MESSAGE = "A retriable";
    public static final String RETRY_AND_LESSEN_WORK_MESSAGE = "A retriable that could indicate the transaction is too large";
    public static final String LESSEN_WORK_MESSAGE = "Transaction too large";
    public static final String NON_RETRIABLE_MESSAGE = "Non Retriable";
    private final Executor executor = ForkJoinPool.commonPool();

    @BeforeAll
    static void beforeAll() {
        // We do this because checking whether an exception is retryable or not requires the version to be set
        FDB.selectAPIVersion(630);
    }

    public static Stream<Arguments> allRetriableCauseTypes() {
        return Stream.of(retriableNonLessenWorkException(),
                        retryAndLessenWorkException(),
                        lessenWorkException())
                .map(Arguments::of);
    }

    public static Stream<Arguments> allCauseTypes() {
        return Stream.concat(allRetriableCauseTypes(),
                Stream.of(nonRetriableException())
                        .map(Arguments::of));
    }

    public static Stream<Arguments> retriableCausesCrossExceptionStyle() {
        return allRetriableCauseTypes()
                .flatMap(exceptionArgs -> Arrays.stream(ExceptionStyle.values()).map(
                        exceptionStyle -> Arguments.of(exceptionArgs.get()[0], exceptionStyle)));
    }

    @Test
    void completesInOnePass() {
        List<Integer> limits = new ArrayList<>();
        run(limit -> {
            limits.add(limit);
            return AsyncUtil.READY_FALSE;
        });
        assertEquals(List.of(10), limits);
    }

    @Test
    void loopsSuccessfully() {
        List<Integer> limits = new ArrayList<>();
        run(limit -> {
            limits.add(limit);
            return limits.size() < 20 ? AsyncUtil.READY_TRUE : AsyncUtil.READY_FALSE;
        });
        assertEquals(20, limits.size());
        // If we ever change the starting limit to be less than the max limit, this should start at 10, but go up
        assertEquals(Set.of(10), Set.copyOf(limits), "The limit should not decrease");
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @EnumSource(ExceptionStyle.class)
    void failsWithRetriableNonLessenWork(ExceptionStyle exceptionStyle) {
        final RuntimeException cause = exceptionStyle.wrap(retriableNonLessenWorkException());
        List<Integer> limits = new ArrayList<>();
        final CompletionException completionException = assertThrows(CompletionException.class,
                () -> run(mockDelay(), 10,
                        limitedRunner -> limitedRunner.setDecreaseLimitAfter(3),
                        runState -> {
                            limits.add(runState.getLimit());
                            return exceptionStyle.hasMore(cause);
                        }));
        assertEquals(cause, completionException.getCause());
        assertThat(limits, Matchers.hasSize(3));
        for (Integer limit : limits) {
            assertEquals(10, limit);
        }
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @EnumSource(ExceptionStyle.class)
    void failWithLessenWork(ExceptionStyle exceptionStyle) {
        final RuntimeException cause = exceptionStyle.wrap(lessenWorkException());
        List<Integer> limits = new ArrayList<>();
        final CompletionException completionException = assertThrows(CompletionException.class,
                () -> run(limit -> {
                    limits.add(limit);
                    return exceptionStyle.hasMore(cause);
                }));
        assertEquals(cause, completionException.getCause());
        assertEquals(10, limits.get(0));
        assertEquals(1, limits.get(limits.size() - 1));
        for (int i = 1; i < limits.size(); i++) {
            assertThat(limits.get(i), Matchers.lessThanOrEqualTo(limits.get(i - 1)));
        }
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @EnumSource(ExceptionStyle.class)
    void failWithRetryAndLessenWork(ExceptionStyle exceptionStyle) {
        // If the exception being thrown is retriable, but could indicate that we are also doing too much
        // work, we want to retry a few times at each limit.
        final RuntimeException cause = exceptionStyle.wrap(retryAndLessenWorkException());
        List<Integer> limits = new ArrayList<>();
        final CompletionException completionException = assertThrows(CompletionException.class,
                () -> run(mockDelay(), 10,
                        limitedRunner -> limitedRunner.setDecreaseLimitAfter(3),
                        runState -> {
                            limits.add(runState.getLimit());
                            return exceptionStyle.hasMore(cause);
                        }));
        assertEquals(cause, completionException.getCause());
        assertEquals(10, limits.get(0));
        assertEquals(1, limits.get(limits.size() - 1));
        for (int i = 1; i < limits.size(); i++) {
            assertThat(buildPointerMessage(limits, i),
                    limits.get(i), Matchers.lessThanOrEqualTo(limits.get(i - 1)));
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(10, limits.get(i), buildPointerMessage(limits, i));
        }
        for (int i = 4; i < 6; i++) {
            String message = buildPointerMessage(limits, i);
            assertThat(message, limits.get(i), Matchers.lessThan(10));
            assertEquals(limits.get(3), limits.get(i), message);
        }
        for (int i = 7; i < 9; i++) {
            String message = buildPointerMessage(limits, i);
            assertThat(message, limits.get(i), Matchers.lessThan(limits.get(5)));
            assertEquals(limits.get(6), limits.get(i), message);
        }
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @EnumSource(ExceptionStyle.class)
    void failWithNonFDBException(ExceptionStyle exceptionStyle) {
        failWithNonRetriableException(exceptionStyle, new NullPointerException());
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @EnumSource(ExceptionStyle.class)
    void failWithNonRetriableException(ExceptionStyle exceptionStyle) {
        failWithNonRetriableException(exceptionStyle, nonRetriableException());
    }

    void failWithNonRetriableException(ExceptionStyle exceptionStyle, final RuntimeException rootCause) {
        final RuntimeException cause = exceptionStyle.wrap(rootCause);
        List<Integer> limits = new ArrayList<>();
        final CompletionException completionException = assertThrows(CompletionException.class,
                () -> run(mockDelay(), 10,
                        limitedRunner -> limitedRunner.setDecreaseLimitAfter(3),
                        runState -> {
                            limits.add(runState.getLimit());
                            return exceptionStyle.hasMore(cause);
                        }));
        assertEquals(cause, completionException.getCause());
        assertEquals(List.of(10), limits);
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @EnumSource(ExceptionStyle.class)
    void increaseAfter(ExceptionStyle exceptionStyle) {
        final RuntimeException cause = exceptionStyle.wrap(lessenWorkException());
        List<Integer> limits = new ArrayList<>();
        // we decrease on the 4th of 5 elements, and increase after 3 successes
        // so that should be something like:
        // 12 12 12 12 08 08 08 12 12 12
        //  1  2  3  4  0  1  2  3  4  0
        //           F                 F
        // Note: I'm picking an even multiple of 4 here, because we do 3/4 decrease and 4/3 and this means there's
        // no rounding
        run(mockDelay(), 12,
                limitedRunner -> limitedRunner.setIncreaseLimitAfter(3),
                runState -> {
                    limits.add(runState.getLimit());
                    if (limits.size() % 5 == 4) {
                        return exceptionStyle.hasMore(cause);
                    } else {
                        return limits.size() < 40 ? AsyncUtil.READY_TRUE : AsyncUtil.READY_FALSE;
                    }
                });
        assertEquals(12, limits.get(0));
        for (int i = 1; i < limits.size(); i++) {
            String message = buildPointerMessage(limits, i);
            if (i % 5 == 4) {
                assertThat(message, limits.get(i), Matchers.lessThan(limits.get(i - 1)));
            } else if (i % 5 == 2 && i > 5) {
                assertThat(message, limits.get(i), Matchers.greaterThan(limits.get(i - 1)));
            } else {
                assertThat(message, limits.get(i), Matchers.equalTo(limits.get(i - 1)));
            }
        }
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @EnumSource(ExceptionStyle.class)
    void increaseAfterABunch(ExceptionStyle exceptionStyle) {
        final RuntimeException cause = exceptionStyle.wrap(lessenWorkException());
        List<Integer> limits = new ArrayList<>();
        AtomicBoolean increasing = new AtomicBoolean(false);
        final int maxLimit = 93;
        final int minLimit = 1;
        run(mockDelay(), maxLimit,
                limitedRunner -> limitedRunner.setIncreaseLimitAfter(5),
                runState -> {
                    limits.add(runState.getLimit());
                    if (runState.getLimit() == maxLimit) {
                        increasing.set(false);
                    }
                    if (runState.getLimit() == minLimit) {
                        increasing.set(true);
                    }
                    if (increasing.get()) {
                        return limits.size() < 200 ? AsyncUtil.READY_TRUE : AsyncUtil.READY_FALSE;
                    } else {
                        return exceptionStyle.hasMore(cause);
                    }
                });
        increasing.set(false);
        assertEquals(93, limits.get(0));
        int changedDirection = 0;
        for (int i = 1; i < limits.size(); i++) {
            String message = buildPointerMessage(limits, i);
            if (increasing.get()) {
                // we want a bunch of successes before increasing
                assertThat(message, limits.get(i), Matchers.greaterThanOrEqualTo(limits.get(i - 1)));
                if (limits.get(i) > 2) {
                    // we don't want to increase too much
                    assertThat(message, limits.get(i), Matchers.lessThan(limits.get(i - 1) * 2));
                }
            } else {
                assertThat(message, limits.get(i), Matchers.lessThan(limits.get(i - 1)));
                if (limits.get(i) > 2) {
                    // we don't want to decrease too much
                    assertThat(message, limits.get(i), Matchers.greaterThan(limits.get(i - 1) / 2));
                }
            }
            if (limits.get(i) == maxLimit) {
                if (increasing.get()) {
                    changedDirection++;
                }
                increasing.set(false);
            }
            if (limits.get(i) == minLimit) {
                if (!increasing.get()) {
                    changedDirection++;
                }
                increasing.set(true);
            }
        }
        // make sure that the constants result in the limiter going all the way down, and back up a couple times
        assertThat(changedDirection, Matchers.greaterThan(4));
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @EnumSource(ExceptionStyle.class)
    void increaseAfterSteps(ExceptionStyle exceptionStyle) {
        // Make sure that when it is increasing, it doesn't just keep increasing, but it waits for some successes at
        // each limit.
        // This just asserts that it goes in batches, increaseAfterABunch asserts that it keeps going up
        final RuntimeException cause = exceptionStyle.wrap(lessenWorkException());
        List<Integer> limits = new ArrayList<>();
        AtomicBoolean increasing = new AtomicBoolean(false);
        final int maxLimit = 93;
        final int minLimit = 1;
        // decrease until we get to the minLimit, than be successful until we get to the maxLimit
        run(mockDelay(), maxLimit,
                limitedRunner -> limitedRunner.setIncreaseLimitAfter(7),
                runState -> {
                    limits.add(runState.getLimit());
                    if (increasing.get()) {
                        if (runState.getLimit() == maxLimit) {
                            return AsyncUtil.READY_FALSE;
                        } else {
                            return AsyncUtil.READY_TRUE;
                        }
                    } else {
                        if (runState.getLimit() == minLimit) {
                            increasing.set(true);
                            return AsyncUtil.READY_TRUE;
                        } else {
                            return exceptionStyle.hasMore(cause);
                        }
                    }
                });
        increasing.set(false);
        assertEquals(93, limits.get(0));
        final int hitMin = limits.indexOf(1);
        int expectedLimit = -1;
        for (int i = hitMin; i < limits.size(); i++) {
            String message = buildPointerMessage(limits, i);
            // it should increase every 7
            if ((i - hitMin) % 7 == 0) {
                assertThat(message, limits.get(i), Matchers.greaterThan(expectedLimit));
                expectedLimit = limits.get(i);
            } else {
                assertEquals(expectedLimit, limits.get(i), message);
            }
        }
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @EnumSource(ExceptionStyle.class)
    void increaseAfterWithRetriableNonLessenWorkException(ExceptionStyle exceptionStyle) {
        // If we're failing intermittently with a retriable exception that doesn't lessen the work,
        // we shouldn't increase the limit.
        final RuntimeException cause = exceptionStyle.wrap(retriableNonLessenWorkException());
        final RuntimeException lessenCause = exceptionStyle.wrap(lessenWorkException());
        List<Integer> limits = new ArrayList<>();
        run(mockDelay(), 12,
                limitedRunner -> limitedRunner.setIncreaseLimitAfter(3),
                runState -> {
                    limits.add(runState.getLimit());
                    if (limits.size() < 3) {
                        // Cause the limit to go down, so that it could go back up, if it were reliably successful
                        return exceptionStyle.hasMore(lessenCause);
                    } else if (limits.size() % 2 == 0) {
                        // Fail every other attempt
                        return exceptionStyle.hasMore(cause);
                    } else {
                        return limits.size() < 20 ? AsyncUtil.READY_TRUE : AsyncUtil.READY_FALSE;
                    }
                });
        assertThat(buildPointerMessage(limits, 3), limits.get(3), Matchers.lessThan(12));
        for (int i = 3; i < limits.size(); i++) {
            assertEquals(limits.get(3), limits.get(i), buildPointerMessage(limits, i));
        }
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @EnumSource(ExceptionStyle.class)
    void doNotIncreaseAfter(ExceptionStyle exceptionStyle) {
        final RuntimeException cause = exceptionStyle.wrap(lessenWorkException());
        List<Integer> limits = new ArrayList<>();
        run(mockDelay(), 12,
                limitedRunner -> { },
                runState -> {
                    limits.add(runState.getLimit());
                    if (runState.getLimit() > 1) {
                        return exceptionStyle.hasMore(cause);
                    } else {
                        return limits.size() < 1000 ? AsyncUtil.READY_TRUE : AsyncUtil.READY_FALSE;
                    }
                });
        assertEquals(12, limits.get(0));
        for (int i = 1; i < limits.size(); i++) {
            String message = buildPointerMessage(limits, i, 3);
            if (limits.get(i - 1) > 1) {
                assertThat(message, limits.get(i), Matchers.lessThan(limits.get(i - 1)));
                assertThat(message, i, Matchers.lessThan(20));
            } else {
                assertEquals(1, limits.get(i), message);
            }
        }
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @EnumSource(ExceptionStyle.class)
    void retryAtMinLimit(ExceptionStyle exceptionStyle) {
        final RuntimeException cause = exceptionStyle.wrap(lessenWorkException());
        List<Integer> limits = new ArrayList<>();
        final CompletionException completionException = assertThrows(CompletionException.class,
                () -> run(limit -> {
                    limits.add(limit);
                    return exceptionStyle.hasMore(cause);
                }));
        assertEquals(cause, completionException.getCause());
        assertEquals(10, limits.get(0));
        assertEquals(1, limits.get(limits.size() - 1));
        assertThat(limits, Matchers.hasSize(Matchers.greaterThan(10)));
        for (int i = 1; i < limits.size(); i++) {
            assertThat(limits.get(i), Matchers.lessThanOrEqualTo(limits.get(i - 1)));
        }
        for (int i = limits.size() - 10; i < limits.size(); i++) {
            assertEquals(1, limits.get(i));
        }
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @MethodSource("allCauseTypes")
    void delaysWhenRetrying(FDBException cause) {
        final ExceptionStyle exceptionStyle = ExceptionStyle.WrappedAsFuture;
        final RuntimeException wrappedCause = exceptionStyle.wrap(cause);
        List<Integer> limits = new ArrayList<>();
        final MockDelay mockDelay = mockDelay();
        final CompletionException completionException = assertThrows(CompletionException.class,
                () -> run(mockDelay, 10, ignored -> { },
                        runState -> {
                            limits.add(runState.getLimit());
                            return exceptionStyle.hasMore(wrappedCause);
                        }));
        assertEquals(wrappedCause, completionException.getCause());
        assertEquals(limits.size() - 1, mockDelay.delays.size());
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @MethodSource("allRetriableCauseTypes")
    void delayResets(FDBException cause) {
        final ExceptionStyle exceptionStyle = ExceptionStyle.WrappedAsFuture;
        final RuntimeException wrappedCause = exceptionStyle.wrap(cause);
        List<Integer> limits = new ArrayList<>();
        final MockDelay mockDelay = mockDelay();
        try (LimitedRunner limitedRunner = new LimitedRunner(executor, 10, mockDelay)) {
            limitedRunner
                    .runAsync(runState -> {
                        limits.add(runState.getLimit());
                        if (limits.size() == 5) {
                            return AsyncUtil.READY_TRUE;
                        } else if (limits.size() < 10) {
                            return exceptionStyle.hasMore(wrappedCause);
                        } else {
                            return AsyncUtil.READY_FALSE;
                        }
                    }, List.of()).join();
        }
        assertEquals(limits.size() - 2, mockDelay.delays.size());
        for (int i = 1; i < mockDelay.delays.size(); i++) {
            String message = buildPointerMessage(mockDelay.delays, i, 6);
            if (i == 4) {
                assertEquals(MockDelay.INITIAL_DELAY_MILLIS, mockDelay.delays.get(4), message);
            } else {
                assertEquals(mockDelay.delays.get(i - 1) * 2, mockDelay.delays.get(i), message);
            }
            System.out.printf("ok [%02d] %06d%n", i, mockDelay.delays.get(i));
        }
    }

    @Test
    void closesFuture() {
        final CompletableFuture<Void> future;
        try (LimitedRunner limitedRunner = new LimitedRunner(executor, 10, mockDelay())) {
            future = limitedRunner.runAsync(runState -> new CompletableFuture<>(), List.of());
        }
        CompletionException completionException = assertThrows(CompletionException.class, future::join);
        assertThat(completionException.getCause(), Matchers.instanceOf(FDBDatabaseRunner.RunnerClosed.class));
    }

    @SuppressWarnings("TryFinallyCanBeTryWithResources") // we call close inside the try block
    @Test
    void closesDelay() {
        final ExceptionStyle exceptionStyle = ExceptionStyle.WrappedAsFuture;
        final RuntimeException wrappedCause = exceptionStyle.wrap(retryAndLessenWorkException());
        final CompletableFuture<Void> future;
        final InfiniteDelay infiniteDelay = new InfiniteDelay();
        LimitedRunner limitedRunner = new LimitedRunner(executor, 10, infiniteDelay);
        try {
            future = limitedRunner.runAsync(runState -> exceptionStyle.hasMore(wrappedCause)
                            .whenComplete((ignoredResult, ignoredError) -> limitedRunner.close()),
                    List.of());
        } finally {
            limitedRunner.close();
        }
        assertTrue(infiniteDelay.future.isCompletedExceptionally());
        CompletionException completionException = assertThrows(CompletionException.class,
                () -> infiniteDelay.future.join());
        assertThat(completionException.getCause(), Matchers.instanceOf(FDBDatabaseRunner.RunnerClosed.class));

        completionException = assertThrows(CompletionException.class, future::join);
        assertThat(completionException.getCause(), Matchers.instanceOf(FDBDatabaseRunner.RunnerClosed.class));
    }

    @ParameterizedTest(name = "{displayName} ({argumentsWithNames})")
    @MethodSource("retriableCausesCrossExceptionStyle")
    void canAddToLoggingDetails(FDBException cause, ExceptionStyle exceptionStyle) {
        final RuntimeException wrappedCause = exceptionStyle.wrap(cause);
        AtomicInteger externalState = new AtomicInteger(0);
        Map<Integer, Boolean> hasFailed = new HashMap<>();
        final String decreasingLimitTitle = "Decreasing limit";
        final String increasingLimitTitle = "Increasing limit";
        final String retryingTitle = "Retrying with same limit";
        final TestHelpers.MatchingAppender matchingAppender = TestHelpers.assertLogs(LimitedRunner.class,
                Pattern.compile("(" + decreasingLimitTitle + "|" + increasingLimitTitle + "|" + retryingTitle + ") .*"),
                () -> {
                    try (LimitedRunner limitedRunner = new LimitedRunner(executor, 10, mockDelay())
                            .setDecreaseLimitAfter(cause.getMessage().equals(RETRIABLE_NON_LESSEN_WORK_MESSAGE) ? 10 : 1)
                            .setIncreaseLimitAfter(1)) {
                        limitedRunner.runAsync(runState -> {
                            final int startingState = externalState.get();
                            System.out.println(startingState + "@" + runState.getLimit());
                            runState.addLogMessageKeyValue("externalState", startingState);
                            if (!hasFailed.getOrDefault(startingState, false)) {
                                hasFailed.put(startingState, true);
                                return exceptionStyle.hasMore(wrappedCause);
                            } else {
                                return CompletableFuture.completedFuture(externalState.addAndGet(runState.getLimit()) < 30);
                            }
                        }, List.of("aConstantDetail", "fishes")).join();
                        return null;
                    }
                });
        assertThat(externalState.get(), Matchers.greaterThanOrEqualTo(30));
        final List<String> formattedEvents = matchingAppender.getFormattedEvents();
        if (cause.getMessage().equals(RETRIABLE_NON_LESSEN_WORK_MESSAGE)) {
            assertThat(formattedEvents, Matchers.everyItem(Matchers.startsWith(retryingTitle + " ")));
        } else if (cause.getMessage().equals(RETRY_AND_LESSEN_WORK_MESSAGE)) {
            assertThat(formattedEvents, TestHelpers.containsInAnyOrderIgnoringDuplicates(
                    Matchers.startsWith(decreasingLimitTitle + " "),
                    Matchers.startsWith(increasingLimitTitle + " ")));
        } else if (cause.getMessage().equals(LESSEN_WORK_MESSAGE)) {
            assertThat(formattedEvents, TestHelpers.containsInAnyOrderIgnoringDuplicates(
                    Matchers.startsWith(decreasingLimitTitle + " "),
                    Matchers.startsWith(increasingLimitTitle + " ")));
        } else {
            fail("Unexpected cause", cause);
        }
        assertThat(formattedEvents, Matchers.everyItem(Matchers.containsString("aConstantDetail=\"fishes\"")));
        Pattern externalStateDetailMatcher = Pattern.compile(".*externalState=\"(\\d+)\".*");
        assertThat(formattedEvents, Matchers.everyItem(Matchers.anyOf(
                // it doesn't really make sense to add state when increasing, but if we did, that wouldn't be a big deal
                Matchers.startsWith(increasingLimitTitle),
                Matchers.matchesRegex(externalStateDetailMatcher))));

        final List<Integer> externalStates = formattedEvents.stream()
                .map(externalStateDetailMatcher::matcher)
                .filter(Matcher::find)
                .map(eventMatch -> Integer.parseInt(eventMatch.group(1)))
                .collect(Collectors.toList());

        for (int i = 1; i < externalStates.size(); i++) {
            assertThat(buildPointerMessage(externalStates, i, 2),
                    externalStates.get(i), Matchers.greaterThan(externalStates.get(i - 1)));
        }
    }

    @Test
    void changeLimitMidRun() {
        List<Integer> limits = new ArrayList<>();
        int initial = 13;
        int middle = 3;
        int end = 40;
        try (LimitedRunner limitedRunner = new LimitedRunner(executor, initial, mockDelay())) {
            limitedRunner.setIncreaseLimitAfter(2)
                    .runAsync(runState -> {
                        limits.add(runState.getLimit());
                        if (limits.size() < 10) {
                            limitedRunner.setMaxLimit(initial);
                        } else if (limits.size() < 20) {
                            limitedRunner.setMaxLimit(middle);
                        } else {
                            limitedRunner.setMaxLimit(end);
                        }
                        return limits.size() < 50 ? AsyncUtil.READY_TRUE : AsyncUtil.READY_FALSE;
                    }, List.of()).join();
        }
        assertEquals(50, limits.size());
        for (int i = 0; i < limits.size(); i++) {
            String message = buildPointerMessage(limits, i);
            if (i < 10) {
                assertEquals(initial, limits.get(i), message);
            } else if (i < 20) {
                assertEquals(middle, limits.get(i), message);
            } else if (i < 40) {
                assertThat(message, limits.get(i),
                        Matchers.allOf(Matchers.greaterThan(middle), Matchers.lessThan(end)));
            } else {
                assertEquals(end, limits.get(i), message);
            }
        }
    }

    @Test
    void changeDecreaseLimitMidRun() {
        final ExceptionStyle exceptionStyle = ExceptionStyle.WrappedAsFuture;
        final RuntimeException cause = exceptionStyle.wrap(retryAndLessenWorkException());
        List<Integer> limits = new ArrayList<>();
        IntFunction<Integer> getDecreaseLimitAfter = index -> {
            if (index < 10) {
                return 3;
            } else if (index < 40) {
                return 7;
            } else {
                return 4;
            }
        };
        try (LimitedRunner limitedRunner = new LimitedRunner(executor, 100_000, mockDelay())) {
            limitedRunner
                    .setDecreaseLimitAfter(1000)
                    .runAsync(runState -> {
                        limits.add(runState.getLimit());
                        limitedRunner.setDecreaseLimitAfter(getDecreaseLimitAfter.apply(limits.size()));

                        if (limits.size() < 60) {
                            return exceptionStyle.hasMore(cause);
                        } else {
                            return AsyncUtil.READY_FALSE;
                        }
                    }, List.of()).join();
        }

        assertEquals(60, limits.size());
        assertEquals(100_000, limits.get(0));
        int lastDecrease = 0;
        for (int i = 1; i < limits.size(); i++) {
            final String message = buildPointerMessage(limits, i, 6);
            if ((i - lastDecrease) % getDecreaseLimitAfter.apply(i) == 0) {
                assertThat(message, limits.get(i), Matchers.lessThan(limits.get(i - 1)));
                lastDecrease = i;
            } else {
                assertEquals(limits.get(i - 1), limits.get(i));
            }
        }
    }

    @Test
    void changeIncreaseLimitMidRun() {
        final ExceptionStyle exceptionStyle = ExceptionStyle.WrappedAsFuture;
        final RuntimeException cause = exceptionStyle.wrap(lessenWorkException());
        List<Integer> limits = new ArrayList<>();
        IntFunction<Integer> getIncreaseLimitAfter = index -> {
            if (index < 10) {
                return 3;
            } else if (index < 40) {
                return 7;
            } else {
                return 4;
            }
        };
        try (LimitedRunner limitedRunner = new LimitedRunner(executor, 100_000, mockDelay())) {
            limitedRunner
                    .setDecreaseLimitAfter(1)
                    .runAsync(runState -> {
                        // setup, start by getting the current limit much lower than the max limit
                        if (limits.size() == 0 && runState.getLimit() > 1) {
                            return exceptionStyle.hasMore(cause);
                        }
                        limits.add(runState.getLimit());
                        limitedRunner.setIncreaseLimitAfter(getIncreaseLimitAfter.apply(limits.size()));

                        if (limits.size() < 60) {
                            return AsyncUtil.READY_TRUE;
                        } else {
                            return AsyncUtil.READY_FALSE;
                        }
                    }, List.of()).join();
        }

        assertEquals(60, limits.size());
        assertEquals(1, limits.get(0));
        int lastIncrease = 0;
        for (int i = 1; i < limits.size(); i++) {
            final String message = buildPointerMessage(limits, i, 6);
            if ((i - lastIncrease) % getIncreaseLimitAfter.apply(i) == 0) {
                assertThat(message, limits.get(i), Matchers.greaterThan(limits.get(i - 1)));
                lastIncrease = i;
            } else {
                assertEquals(limits.get(i - 1), limits.get(i));
            }
        }
    }

    @Test
    void decreaseMaxDecreaseRetries() {
        final ExceptionStyle exceptionStyle = ExceptionStyle.WrappedAsFuture;
        final RuntimeException cause = exceptionStyle.wrap(lessenWorkException());
        List<Integer> limits = new ArrayList<>();
        try (LimitedRunner limitedRunner = new LimitedRunner(executor, 100_000, mockDelay())) {
            final CompletionException completionException = assertThrows(CompletionException.class,
                    () -> limitedRunner
                            .setDecreaseLimitAfter(1)
                            .setMaxDecreaseRetries(100)
                            .runAsync(runState -> {
                                limits.add(runState.getLimit());
                                if (limits.size() < 100) {
                                    return exceptionStyle.hasMore(cause);
                                } else if (limits.size() == 100) {
                                    return AsyncUtil.READY_TRUE;
                                } else if (limits.size() < 110) {
                                    limitedRunner.setMaxDecreaseRetries(10);
                                    return AsyncUtil.READY_TRUE;
                                } else {
                                    return exceptionStyle.hasMore(cause);
                                }
                            }, List.of()).join());
            assertEquals(cause, completionException.getCause());
        }
        assertEquals(120, limits.size());
    }

    @Test
    void increaseMaxDecreaseRetries() {
        final ExceptionStyle exceptionStyle = ExceptionStyle.WrappedAsFuture;
        final RuntimeException cause = exceptionStyle.wrap(lessenWorkException());
        List<Integer> limits = new ArrayList<>();
        try (LimitedRunner limitedRunner = new LimitedRunner(executor, 100_000, mockDelay())) {
            final CompletionException completionException = assertThrows(CompletionException.class,
                    () -> limitedRunner
                            .setDecreaseLimitAfter(1)
                            .setMaxDecreaseRetries(10)
                            .runAsync(runState -> {
                                limits.add(runState.getLimit());
                                if (limits.size() < 10) {
                                    return exceptionStyle.hasMore(cause);
                                } else if (limits.size() == 10) {
                                    return AsyncUtil.READY_TRUE;
                                } else if (limits.size() < 20) {
                                    limitedRunner.setMaxDecreaseRetries(99);
                                    return AsyncUtil.READY_TRUE;
                                } else {
                                    return exceptionStyle.hasMore(cause);
                                }
                            }, List.of()).join());
            assertEquals(cause, completionException.getCause());
        }
        assertEquals(119, limits.size());
    }

    @Nonnull
    private String buildPointerMessage(final List<?> limits, final int i, final int width) {
        String format = "%" + width + "s";
        String space = String.format(format, "_");
        String pointer = "  " + new String(new char[width]).replace('\0', '^');
        return limits.stream()
                       .map(limit -> String.format("%" + width + "s", limit))
                       .collect(Collectors.joining(", ", "[", "]\n"))
               + limits.stream().limit(i).map(vignored -> space)
                       .collect(Collectors.joining(", ", "[", pointer));
    }

    @Nonnull
    private String buildPointerMessage(final List<Integer> limits, final int i) {
        return buildPointerMessage(limits, i, 2);
    }

    @Nonnull
    private static FDBException retriableNonLessenWorkException() {
        return new FDBException(RETRIABLE_NON_LESSEN_WORK_MESSAGE, FDBError.FUTURE_VERSION.code());
    }

    @Nonnull
    private static FDBException retryAndLessenWorkException() {
        return new FDBException(RETRY_AND_LESSEN_WORK_MESSAGE,
                FDBError.TRANSACTION_TOO_OLD.code());
    }

    @Nonnull
    private static FDBException lessenWorkException() {
        return new FDBException(LESSEN_WORK_MESSAGE, FDBError.TRANSACTION_TOO_LARGE.code());
    }

    @Nonnull
    private static FDBException nonRetriableException() {
        return new FDBException(NON_RETRIABLE_MESSAGE, FDBError.INTERNAL_ERROR.code());
    }

    private void run(final Function<Integer, CompletableFuture<Boolean>> runner) {
        run(mockDelay(), 10, vignored -> { }, runState -> runner.apply(runState.getLimit()));
    }

    private void run(final MockDelay exponentialDelay, final int maxLimit,
                     final Consumer<LimitedRunner> updateConfig,
                     final LimitedRunner.Runner runner) {
        try (LimitedRunner limitedRunner = new LimitedRunner(executor, maxLimit, exponentialDelay)) {
            updateConfig.accept(limitedRunner);
            limitedRunner
                    .runAsync(runner, List.of())
                    .join();
        }
    }

    @Nonnull
    private MockDelay mockDelay() {
        return new MockDelay();
    }

    enum ExceptionStyle {
        Wrapped(true, false),
        Raw(false, false),
        WrappedAsFuture(true, true),
        RawAsFuture(false, true);

        private final boolean isWrapped;
        private final boolean isFuture;

        ExceptionStyle(final boolean isWrapped, final boolean isFuture) {

            this.isWrapped = isWrapped;
            this.isFuture = isFuture;
        }

        RuntimeException wrap(FDBException rawCause) {
            return isWrapped ? new FDBExceptions.FDBStoreRetriableException(rawCause) : rawCause;
        }

        RuntimeException wrap(RuntimeException rawCause) {
            return isWrapped ? new RuntimeException(rawCause) : rawCause;
        }

        CompletableFuture<Boolean> hasMore(RuntimeException cause) {
            if (isFuture) {
                final CompletableFuture<Boolean> future = new CompletableFuture<>();
                future.completeExceptionally(cause);
                return future;
            } else {
                throw cause;
            }
        }
    }

    private static class InfiniteDelay extends ExponentialDelay {
        public CompletableFuture<Void> future = new CompletableFuture<>();

        public InfiniteDelay() {
            super(Long.MAX_VALUE, Long.MAX_VALUE);
        }

        @Nonnull
        @Override
        public CompletableFuture<Void> delayedFuture(final long nextDelayMillis) {
            return future;
        }
    }

    private static class MockDelay extends ExponentialDelay {

        public static final int INITIAL_DELAY_MILLIS = 3000;
        private final List<Long> delays = new ArrayList<>();

        public MockDelay() {
            super(INITIAL_DELAY_MILLIS, 1000000);
        }

        @Override
        protected double randomDouble() {
            // remove the randomness at this level, so that we can more easily assert about behavior,
            // lower-level tests assert that it does the right thing with the randomness
            return 1;
        }

        @Nonnull
        @Override
        protected CompletableFuture<Void> delayedFuture(final long nextDelayMillis) {
            delays.add(nextDelayMillis);
            return CompletableFuture.completedFuture(null);
        }
    }

}