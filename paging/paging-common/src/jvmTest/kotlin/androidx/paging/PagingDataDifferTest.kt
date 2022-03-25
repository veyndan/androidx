/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.paging

import androidx.paging.LoadState.Loading
import androidx.paging.LoadState.NotLoading
import androidx.paging.LoadType.PREPEND
import androidx.paging.PageEvent.Drop
import androidx.paging.PagingSource.LoadResult
import androidx.testutils.DirectDispatcher
import androidx.testutils.TestDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPagingApi::class, ExperimentalStdlibApi::class)
class PagingDataDifferTest {
    private val testScope = TestScope(UnconfinedTestDispatcher())

    @BeforeTest
    fun beforeTest() {
        Dispatchers.setMain(testScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
    }

    @AfterTest
    fun afterTest() {
        Dispatchers.resetMain()
    }

    @Test
    fun collectFrom_static() = testScope.runTest {
        withContext(coroutineContext) {
            val differ = SimpleDiffer(dummyDifferCallback)
            val receiver = UiReceiverFake()

            val job1 = launch {
                differ.collectFrom(infinitelySuspendingPagingData(receiver))
            }
            advanceUntilIdle()
            job1.cancel()

            val job2 = launch {
                differ.collectFrom(PagingData.empty())
            }
            advanceUntilIdle()
            job2.cancel()

            // Static replacement should also replace the UiReceiver from previous generation.
            differ.retry()
            differ.refresh()
            advanceUntilIdle()

            assertFalse { receiver.retryEvents.isNotEmpty() }
            assertFalse { receiver.refreshEvents.isNotEmpty() }
        }
    }

    @Test
    fun collectFrom_twice() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)

        launch { differ.collectFrom(infinitelySuspendingPagingData()) }
            .cancel()
        launch { differ.collectFrom(infinitelySuspendingPagingData()) }
            .cancel()
    }

    @Test
    fun collectFrom_twiceConcurrently() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)

        val job1 = launch {
            differ.collectFrom(infinitelySuspendingPagingData())
        }

        // Ensure job1 is running.
        assertTrue { job1.isActive }

        val job2 = launch {
            differ.collectFrom(infinitelySuspendingPagingData())
        }

        // job2 collection should complete job1 but not cancel.
        assertFalse { job1.isCancelled }
        assertTrue { job1.isCompleted }
        job2.cancel()
    }

    @Test
    fun retry() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val receiver = UiReceiverFake()

        val job = launch {
            differ.collectFrom(infinitelySuspendingPagingData(receiver))
        }

        differ.retry()

        assertEquals(1, receiver.retryEvents.size)

        job.cancel()
    }

    @Test
    fun refresh() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val receiver = UiReceiverFake()

        val job = launch {
            differ.collectFrom(infinitelySuspendingPagingData(receiver))
        }

        differ.refresh()

        assertEquals(1, receiver.refreshEvents.size)

        job.cancel()
    }

    @Test
    fun fetch_loadHintResentWhenUnfulfilled() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)

        val pageEventCh = Channel<PageEvent<Int>>(Channel.UNLIMITED)
        pageEventCh.trySend(
            localRefresh(
                pages = listOf(TransformablePage(0, listOf(0, 1))),
                placeholdersBefore = 4,
                placeholdersAfter = 4,
            )
        )
        pageEventCh.trySend(
            localPrepend(
                pages = listOf(TransformablePage(-1, listOf(-1, -2))),
                placeholdersBefore = 2,
            )
        )
        pageEventCh.trySend(
            localAppend(
                pages = listOf(TransformablePage(1, listOf(2, 3))),
                placeholdersAfter = 2,
            )
        )

        val receiver = UiReceiverFake()
        val job = launch {
            differ.collectFrom(
                // Filter the original list of 10 items to 5, removing even numbers.
                PagingData(pageEventCh.consumeAsFlow(), receiver).filter { it % 2 != 0 }
            )
        }

        // Initial state:
        // [null, null, [-1], [1], [3], null, null]
        assertNull(differ[0])
        assertEquals(
            listOf(
                ViewportHint.Initial(
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0,
                ),
                ViewportHint.Access(
                    pageOffset = -1,
                    indexInPage = -2,
                    presentedItemsBefore = -2,
                    presentedItemsAfter = 4,
                    originalPageOffsetFirst = -1,
                    originalPageOffsetLast = 1
                ),
            ),
            receiver.hints
        )

        // Insert a new page, PagingDataDiffer should try to resend hint since index 0 still points
        // to a placeholder:
        // [null, null, [], [-1], [1], [3], null, null]
        pageEventCh.trySend(
            localPrepend(
                pages = listOf(TransformablePage(-2, listOf())),
                placeholdersBefore = 2,
            )
        )
        assertEquals(
            listOf(
                ViewportHint.Access(
                    pageOffset = -2,
                    indexInPage = -2,
                    presentedItemsBefore = -2,
                    presentedItemsAfter = 4,
                    originalPageOffsetFirst = -2,
                    originalPageOffsetLast = 1
                )
            ),
            receiver.hints
        )

        // Now index 0 has been loaded:
        // [[-3], [], [-1], [1], [3], null, null]
        pageEventCh.trySend(
            localPrepend(
                pages = listOf(TransformablePage(-3, listOf(-3, -4))),
                placeholdersBefore = 0,
                source = loadStates(prepend = NotLoading.Complete)
            )
        )
        assertTrue(receiver.hints.isEmpty())

        // This index points to a valid placeholder that ends up removed by filter().
        assertNull(differ[5])
        assertEquals(
            listOf(
                ViewportHint.Access(
                    pageOffset = 1,
                    indexInPage = 2,
                    presentedItemsBefore = 5,
                    presentedItemsAfter = -2,
                    originalPageOffsetFirst = -3,
                    originalPageOffsetLast = 1
                )
            ),
            receiver.hints
        )

        // Should only resend the hint for index 5, since index 0 has already been loaded:
        // [[-3], [], [-1], [1], [3], [], null, null]
        pageEventCh.trySend(
            localAppend(
                pages = listOf(TransformablePage(2, listOf())),
                placeholdersAfter = 2,
                source = loadStates(prepend = NotLoading.Complete)
            )
        )
        assertEquals(
            listOf(
                ViewportHint.Access(
                    pageOffset = 2,
                    indexInPage = 1,
                    presentedItemsBefore = 5,
                    presentedItemsAfter = -2,
                    originalPageOffsetFirst = -3,
                    originalPageOffsetLast = 2
                )
            ),
            receiver.hints
        )

        // Index 5 hasn't loaded, but we are at the end of the list:
        // [[-3], [], [-1], [1], [3], [], [5]]
        pageEventCh.trySend(
            localAppend(
                pages = listOf(TransformablePage(3, listOf(4, 5))),
                placeholdersAfter = 0,
                source = loadStates(prepend = NotLoading.Complete, append = NotLoading.Complete)
            )
        )
        assertTrue(receiver.hints.isEmpty())

        job.cancel()
    }

    @Test
    fun fetch_loadHintResentUnlessPageDropped() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)

        val pageEventCh = Channel<PageEvent<Int>>(Channel.UNLIMITED)
        pageEventCh.trySend(
            localRefresh(
                pages = listOf(TransformablePage(0, listOf(0, 1))),
                placeholdersBefore = 4,
                placeholdersAfter = 4,
            )
        )
        pageEventCh.trySend(
            localPrepend(
                pages = listOf(TransformablePage(-1, listOf(-1, -2))),
                placeholdersBefore = 2,
            )
        )
        pageEventCh.trySend(
            localAppend(
                pages = listOf(TransformablePage(1, listOf(2, 3))),
                placeholdersAfter = 2,
            )
        )

        val receiver = UiReceiverFake()
        val job = launch {
            differ.collectFrom(
                // Filter the original list of 10 items to 5, removing even numbers.
                PagingData(pageEventCh.consumeAsFlow(), receiver).filter { it % 2 != 0 }
            )
        }

        // Initial state:
        // [null, null, [-1], [1], [3], null, null]
        assertNull(differ[0])
        assertEquals(
            listOf(
                ViewportHint.Initial(
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0,
                ),
                ViewportHint.Access(
                    pageOffset = -1,
                    indexInPage = -2,
                    presentedItemsBefore = -2,
                    presentedItemsAfter = 4,
                    originalPageOffsetFirst = -1,
                    originalPageOffsetLast = 1
                ),
            ),
            receiver.hints
        )

        // Insert a new page, PagingDataDiffer should try to resend hint since index 0 still points
        // to a placeholder:
        // [null, null, [], [-1], [1], [3], null, null]
        pageEventCh.trySend(
            localPrepend(
                pages = listOf(TransformablePage(-2, listOf())),
                placeholdersBefore = 2,
            )
        )
        assertEquals(
            listOf(
                ViewportHint.Access(
                    pageOffset = -2,
                    indexInPage = -2,
                    presentedItemsBefore = -2,
                    presentedItemsAfter = 4,
                    originalPageOffsetFirst = -2,
                    originalPageOffsetLast = 1
                )
            ),
            receiver.hints
        )

        // Drop the previous page, which reset resendable index state in the PREPEND direction.
        // [null, null, [-1], [1], [3], null, null]
        pageEventCh.trySend(
            Drop(
                loadType = PREPEND,
                minPageOffset = -2,
                maxPageOffset = -2,
                placeholdersRemaining = 2
            )
        )

        // Re-insert the previous page, which should not trigger resending the index due to
        // previous page drop:
        // [[-3], [], [-1], [1], [3], null, null]
        pageEventCh.trySend(
            localPrepend(
                pages = listOf(TransformablePage(-2, listOf())),
                placeholdersBefore = 2,
            )
        )

        job.cancel()
    }

    @Test
    fun peek() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val pageEventCh = Channel<PageEvent<Int>>(Channel.UNLIMITED)
        pageEventCh.trySend(
            localRefresh(
                pages = listOf(TransformablePage(0, listOf(0, 1))),
                placeholdersBefore = 4,
                placeholdersAfter = 4,
            )
        )
        pageEventCh.trySend(
            localPrepend(
                pages = listOf(TransformablePage(-1, listOf(-1, -2))),
                placeholdersBefore = 2,
            )
        )
        pageEventCh.trySend(
            localAppend(
                pages = listOf(TransformablePage(1, listOf(2, 3))),
                placeholdersAfter = 2,
            )
        )

        val receiver = UiReceiverFake()
        val job = launch {
            differ.collectFrom(
                // Filter the original list of 10 items to 5, removing even numbers.
                PagingData(pageEventCh.consumeAsFlow(), receiver)
            )
        }

        // Check that peek fetches the correct placeholder
        assertEquals(0, differ.peek(4))

        // Check that peek fetches the correct placeholder
        assertNull(differ.peek(0))

        // Check that peek does not trigger page fetch.
        assertEquals(
            listOf<ViewportHint>(
                ViewportHint.Initial(
                    presentedItemsBefore = 1,
                    presentedItemsAfter = 1,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0,
                )
            ),
            receiver.hints
        )

        job.cancel()
    }

    @Test
    fun initialHint_emptyRefresh() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val pageEventCh = Channel<PageEvent<Int>>(Channel.UNLIMITED)
        val uiReceiver = UiReceiverFake()
        val job = launch {
            differ.collectFrom(PagingData(pageEventCh.consumeAsFlow(), uiReceiver))
        }

        pageEventCh.trySend(
            localRefresh(pages = listOf(TransformablePage(emptyList())))
        )

        assertEquals(
            listOf(ViewportHint.Initial(0, 0, 0, 0)),
            uiReceiver.hints
        )

        job.cancel()
    }

    @Test
    fun onPagingDataPresentedListener_empty() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val listenerEvents = mutableListOf<Unit>()
        differ.addOnPagesUpdatedListener {
            listenerEvents.add(Unit)
        }

        differ.collectFrom(PagingData.empty())
        assertEquals(1, listenerEvents.size)

        // No change to LoadState or presented list should still trigger the listener.
        differ.collectFrom(PagingData.empty())
        assertEquals(2, listenerEvents.size)

        val pager = Pager(PagingConfig(pageSize = 1)) { TestPagingSource(items = listOf()) }
        val job = testScope.launch {
            pager.flow.collectLatest { differ.collectFrom(it) }
        }

        // Should wait for new generation to load and apply it first.
        assertEquals(2, listenerEvents.size)

        advanceUntilIdle()
        assertEquals(3, listenerEvents.size)

        job.cancel()
    }

    @Test
    fun onPagingDataPresentedListener_insertDrop() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val listenerEvents = mutableListOf<Unit>()
        differ.addOnPagesUpdatedListener {
            listenerEvents.add(Unit)
        }

        val pager = Pager(PagingConfig(pageSize = 1, maxSize = 4), initialKey = 50) {
            TestPagingSource()
        }
        val job = testScope.launch {
            pager.flow.collectLatest { differ.collectFrom(it) }
        }

        // Should wait for new generation to load and apply it first.
        assertEquals(0, listenerEvents.size)

        advanceUntilIdle()
        assertEquals(1, listenerEvents.size)

        // Trigger PREPEND.
        differ[50]
        assertEquals(1, listenerEvents.size)
        advanceUntilIdle()
        assertEquals(2, listenerEvents.size)

        // Trigger APPEND + Drop
        differ[52]
        assertEquals(2, listenerEvents.size)
        advanceUntilIdle()
        assertEquals(4, listenerEvents.size)

        job.cancel()
    }

    @Test
    fun onPagingDataPresentedFlow_empty() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val listenerEvents = mutableListOf<Unit>()
        val job1 = testScope.launch {
            differ.onPagesUpdatedFlow.collect {
                listenerEvents.add(Unit)
            }
        }

        differ.collectFrom(PagingData.empty())
        assertEquals(1, listenerEvents.size)

        // No change to LoadState or presented list should still trigger the listener.
        differ.collectFrom(PagingData.empty())
        assertEquals(2, listenerEvents.size)

        val pager = Pager(PagingConfig(pageSize = 1)) { TestPagingSource(items = listOf()) }
        val job2 = testScope.launch {
            pager.flow.collectLatest { differ.collectFrom(it) }
        }

        // Should wait for new generation to load and apply it first.
        assertEquals(2, listenerEvents.size)

        advanceUntilIdle()
        assertEquals(3, listenerEvents.size)

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun onPagingDataPresentedFlow_insertDrop() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val listenerEvents = mutableListOf<Unit>()
        val job1 = testScope.launch {
            differ.onPagesUpdatedFlow.collect {
                listenerEvents.add(Unit)
            }
        }

        val pager = Pager(PagingConfig(pageSize = 1, maxSize = 4), initialKey = 50) {
            TestPagingSource()
        }
        val job2 = testScope.launch {
            pager.flow.collectLatest { differ.collectFrom(it) }
        }

        // Should wait for new generation to load and apply it first.
        assertEquals(0, listenerEvents.size)

        advanceUntilIdle()
        assertEquals(1, listenerEvents.size)

        // Trigger PREPEND.
        differ[50]
        assertEquals(1, listenerEvents.size)
        advanceUntilIdle()
        assertEquals(2, listenerEvents.size)

        // Trigger APPEND + Drop
        differ[52]
        assertEquals(2, listenerEvents.size)
        advanceUntilIdle()
        assertEquals(4, listenerEvents.size)

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun onPagingDataPresentedFlow_buffer() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val listenerEvents = mutableListOf<Unit>()

        // Trigger update, which should get ignored due to onPagesUpdatedFlow being hot.
        differ.collectFrom(PagingData.empty())

        val job = testScope.launch {
            differ.onPagesUpdatedFlow.collect {
                listenerEvents.add(Unit)
                // Await advanceUntilIdle() before accepting another event.
                delay(100)
            }
        }

        // Previous update before collection happened should be ignored.
        assertEquals(0, listenerEvents.size)

        // Trigger update; should get immediately received.
        differ.collectFrom(PagingData.empty())
        assertEquals(1, listenerEvents.size)

        // Trigger 64 update while collector is still processing; should all get buffered.
        repeat(64) { differ.collectFrom(PagingData.empty()) }

        // Trigger another update while collector is still processing; should cause event to drop.
        differ.collectFrom(PagingData.empty())

        // Await all; we should now receive the buffered event.
        advanceUntilIdle()
        assertEquals(65, listenerEvents.size)

        job.cancel()
    }

    @Test
    fun loadStateFlow_synchronouslyUpdates() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        var combinedLoadStates: CombinedLoadStates? = null
        var itemCount = -1
        val loadStateJob = launch {
            differ.loadStateFlow.collect {
                combinedLoadStates = it
                itemCount = differ.size
            }
        }

        val pager = Pager(
            config = PagingConfig(
                pageSize = 10,
                enablePlaceholders = false,
                initialLoadSize = 10,
                prefetchDistance = 1
            ),
            initialKey = 50
        ) { TestPagingSource() }
        val job = launch {
            pager.flow.collectLatest { differ.collectFrom(it) }
        }

        // Initial refresh
        advanceUntilIdle()
        assertEquals(localLoadStatesOf(), combinedLoadStates)
        assertEquals(10, itemCount)
        assertEquals(10, differ.size)

        // Append
        differ[9]
        advanceUntilIdle()
        assertEquals(localLoadStatesOf(), combinedLoadStates)
        assertEquals(20, itemCount)
        assertEquals(20, differ.size)

        // Prepend
        differ[0]
        advanceUntilIdle()
        assertEquals(localLoadStatesOf(), combinedLoadStates)
        assertEquals(30, itemCount)
        assertEquals(30, differ.size)

        job.cancel()
        loadStateJob.cancel()
    }

    @Test
    fun loadStateFlow_hasNoInitialValue() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)

        // Should not immediately emit without a real value to a new collector.
        val combinedLoadStates = mutableListOf<CombinedLoadStates>()
        val loadStateJob = launch {
            differ.loadStateFlow.collect {
                combinedLoadStates.add(it)
            }
        }
        assertTrue(combinedLoadStates.isEmpty())

        // Add a real value and now we should emit to collector.
        differ.collectFrom(
            PagingData.empty(
                sourceLoadStates = loadStates(
                    prepend = NotLoading.Complete,
                    append = NotLoading.Complete
                )
            )
        )
        assertContentEquals(
            listOf(
                localLoadStatesOf(
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete,
                )
            ),
            combinedLoadStates
        )

        // Should emit real values to new collectors immediately
        val newCombinedLoadStates = mutableListOf<CombinedLoadStates>()
        val newLoadStateJob = launch {
            differ.loadStateFlow.collect {
                newCombinedLoadStates.add(it)
            }
        }
        assertContentEquals(
            listOf(
                localLoadStatesOf(
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete,
                )
            ),
            newCombinedLoadStates
        )

        loadStateJob.cancel()
        newLoadStateJob.cancel()
    }

    @Test
    fun loadStateFlow_preservesLoadStatesOnEmptyList() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)

        // Should not immediately emit without a real value to a new collector.
        val combinedLoadStates = mutableListOf<CombinedLoadStates>()
        val loadStateJob = launch {
            differ.loadStateFlow.collect {
                combinedLoadStates.add(it)
            }
        }
        assertTrue(combinedLoadStates.getAllAndClear().isEmpty())

        // Send a static list without load states, which should not send anything.
        differ.collectFrom(PagingData.empty())
        assertTrue(combinedLoadStates.getAllAndClear().isEmpty())

        // Send a real LoadStateUpdate.
        differ.collectFrom(
            PagingData(
                flow = flowOf(
                    remoteLoadStateUpdate(
                        refreshLocal = Loading,
                        prependLocal = Loading,
                        appendLocal = Loading,
                        refreshRemote = Loading,
                        prependRemote = Loading,
                        appendRemote = Loading,
                    )
                ),
                receiver = PagingData.NOOP_RECEIVER,
            )
        )
        assertContentEquals(
            listOf(
                remoteLoadStatesOf(
                    refresh = Loading,
                    prepend = Loading,
                    append = Loading,
                    refreshLocal = Loading,
                    prependLocal = Loading,
                    appendLocal = Loading,
                    refreshRemote = Loading,
                    prependRemote = Loading,
                    appendRemote = Loading,
                )
            ),
            combinedLoadStates.getAllAndClear()
        )

        // Send a static list without load states, which should preserve the previous state.
        differ.collectFrom(PagingData.empty())
        // Existing observers should not receive any updates
        assertTrue(combinedLoadStates.getAllAndClear().isEmpty())
        // New observers should receive the previous state.
        val newCombinedLoadStates = mutableListOf<CombinedLoadStates>()
        val newLoadStateJob = launch {
            differ.loadStateFlow.collect {
                newCombinedLoadStates.add(it)
            }
        }
        assertContentEquals(
            listOf(
                remoteLoadStatesOf(
                    refresh = Loading,
                    prepend = Loading,
                    append = Loading,
                    refreshLocal = Loading,
                    prependLocal = Loading,
                    appendLocal = Loading,
                    refreshRemote = Loading,
                    prependRemote = Loading,
                    appendRemote = Loading,
                )
            ),
            newCombinedLoadStates.getAllAndClear()
        )

        loadStateJob.cancel()
        newLoadStateJob.cancel()
    }

    @Test
    fun loadStateFlow_preservesLoadStatesOnStaticList() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)

        // Should not immediately emit without a real value to a new collector.
        val combinedLoadStates = mutableListOf<CombinedLoadStates>()
        val loadStateJob = launch {
            differ.loadStateFlow.collect {
                combinedLoadStates.add(it)
            }
        }
        assertTrue(combinedLoadStates.getAllAndClear().isEmpty())

        // Send a static list without load states, which should not send anything.
        differ.collectFrom(PagingData.from(listOf(1)))
        assertTrue(combinedLoadStates.getAllAndClear().isEmpty())

        // Send a real LoadStateUpdate.
        differ.collectFrom(
            PagingData(
                flow = flowOf(
                    remoteLoadStateUpdate(
                        refreshLocal = Loading,
                        prependLocal = Loading,
                        appendLocal = Loading,
                        refreshRemote = Loading,
                        prependRemote = Loading,
                        appendRemote = Loading,
                    )
                ),
                receiver = PagingData.NOOP_RECEIVER,
            )
        )
        assertContentEquals(
            listOf(
                remoteLoadStatesOf(
                    refresh = Loading,
                    prepend = Loading,
                    append = Loading,
                    refreshLocal = Loading,
                    prependLocal = Loading,
                    appendLocal = Loading,
                    refreshRemote = Loading,
                    prependRemote = Loading,
                    appendRemote = Loading,
                )
            ),
            combinedLoadStates.getAllAndClear()
        )

        // Send a static list without load states, which should preserve the previous state.
        differ.collectFrom(PagingData.from(listOf(1)))
        // Existing observers should not receive any updates
        assertTrue(combinedLoadStates.getAllAndClear().isEmpty())
        // New observers should receive the previous state.
        val newCombinedLoadStates = mutableListOf<CombinedLoadStates>()
        val newLoadStateJob = launch {
            differ.loadStateFlow.collect {
                newCombinedLoadStates.add(it)
            }
        }
        assertContentEquals(
            listOf(
                remoteLoadStatesOf(
                    refresh = Loading,
                    prepend = Loading,
                    append = Loading,
                    refreshLocal = Loading,
                    prependLocal = Loading,
                    appendLocal = Loading,
                    refreshRemote = Loading,
                    prependRemote = Loading,
                    appendRemote = Loading,
                )
            ),
            newCombinedLoadStates.getAllAndClear()
        )

        loadStateJob.cancel()
        newLoadStateJob.cancel()
    }

    @Test
    fun addLoadStateListener_SynchronouslyUpdates() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        withContext(coroutineContext) {
            var combinedLoadStates: CombinedLoadStates? = null
            var itemCount = -1
            differ.addLoadStateListener {
                combinedLoadStates = it
                itemCount = differ.size
            }

            val pager = Pager(
                config = PagingConfig(
                    pageSize = 10,
                    enablePlaceholders = false,
                    initialLoadSize = 10,
                    prefetchDistance = 1
                ),
                initialKey = 50
            ) { TestPagingSource() }
            val job = launch {
                pager.flow.collectLatest { differ.collectFrom(it) }
            }

            // Initial refresh
            advanceUntilIdle()
            assertEquals(localLoadStatesOf(), combinedLoadStates)
            assertEquals(10, itemCount)
            assertEquals(10, differ.size)

            // Append
            differ[9]
            advanceUntilIdle()
            assertEquals(localLoadStatesOf(), combinedLoadStates)
            assertEquals(20, itemCount)
            assertEquals(20, differ.size)

            // Prepend
            differ[0]
            advanceUntilIdle()
            assertEquals(localLoadStatesOf(), combinedLoadStates)
            assertEquals(30, itemCount)
            assertEquals(30, differ.size)

            job.cancel()
        }
    }

    @Test
    fun addLoadStateListener_hasNoInitialValue() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val combinedLoadStateCapture = CombinedLoadStatesCapture()

        // Adding a new listener without a real value should not trigger it.
        differ.addLoadStateListener(combinedLoadStateCapture)
        assertTrue(combinedLoadStateCapture.newEvents().isEmpty())

        // Add a real value and now the listener should trigger.
        differ.collectFrom(
            PagingData.empty(
                sourceLoadStates = loadStates(
                    prepend = NotLoading.Complete,
                    append = NotLoading.Complete,
                )
            )
        )
        assertContentEquals(
            listOf(
                localLoadStatesOf(
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete,
                )
            ),
            combinedLoadStateCapture.newEvents()
        )

        // Should emit real values to new listeners immediately
        val newCombinedLoadStateCapture = CombinedLoadStatesCapture()
        differ.addLoadStateListener(newCombinedLoadStateCapture)
        assertContentEquals(
            listOf(
                localLoadStatesOf(
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete,
                )
            ),
            newCombinedLoadStateCapture.newEvents()
        )
    }

    @Test
    fun uncaughtException() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val pager = Pager(
            PagingConfig(1),
        ) {
            object : PagingSource<Int, Int>() {
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
                    throw IllegalStateException()
                }

                override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
            }
        }

        val pagingData = pager.flow.first()
        val deferred = testScope.async(Job()) {
            differ.collectFrom(pagingData)
        }

        advanceUntilIdle()
        assertFailsWith<IllegalStateException> { deferred.await() }
    }

    @Test
    fun handledLoadResultInvalid() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        var generation = 0
        val pager = Pager(
            PagingConfig(1),
        ) {
            TestPagingSource().also {
                if (generation == 0) {
                    it.nextLoadResult = PagingSource.LoadResult.Invalid()
                }
                generation++
            }
        }

        val pagingData = pager.flow.first()
        val deferred = testScope.async {
            // only returns if flow is closed, or work canclled, or exception thrown
            // in this case it should cancel due LoadResult.Invalid causing collectFrom to return
            differ.collectFrom(pagingData)
        }

        advanceUntilIdle()
        // this will return only if differ.collectFrom returns
        deferred.await()
    }

    @Test
    fun refresh_loadStates() = params().forEach { refresh_loadStates(it) }

    private fun refresh_loadStates(collectWithCachedIn: Boolean) = runTest(
        initialKey = 50,
        collectWithCachedIn = collectWithCachedIn
    ) { differ, loadDispatcher, pagingSources ->
        val collectLoadStates = differ.collectLoadStates()

        // execute queued initial REFRESH
        loadDispatcher.queue.removeLastOrNull()?.run()

        assertContentEquals(50 until 59, differ.snapshot())
        assertContentEquals(
            listOf(
                localLoadStatesOf(refreshLocal = Loading),
                localLoadStatesOf(),
            ),
            differ.newCombinedLoadStates()
        )

        differ.refresh()

        // execute second REFRESH load
        loadDispatcher.queue.removeLastOrNull()?.run()

        // second refresh still loads from initialKey = 50 because anchorPosition/refreshKey is null
        assertEquals(2, pagingSources.size)
        assertContentEquals(50 until 59, differ.snapshot())
        assertContentEquals(
            listOf(
                localLoadStatesOf(refreshLocal = Loading),
                localLoadStatesOf()
            ),
            differ.newCombinedLoadStates()
        )

        collectLoadStates.cancel()
    }

    @Test
    fun refresh_loadStates_afterEndOfPagination() =
        params().forEach { refresh_loadStates_afterEndOfPagination(it) }

    private fun refresh_loadStates_afterEndOfPagination(collectWithCachedIn: Boolean) = runTest(collectWithCachedIn = collectWithCachedIn) { differ, loadDispatcher, _ ->
        val loadStateCallbacks = mutableListOf<CombinedLoadStates>()
        differ.addLoadStateListener {
            loadStateCallbacks.add(it)
        }
        val collectLoadStates = differ.collectLoadStates()
        // execute initial refresh
        loadDispatcher.queue.removeLastOrNull()?.run()
        assertContentEquals(0 until 9, differ.snapshot())
        assertContentEquals(
            listOf(
                localLoadStatesOf(
                    refreshLocal = Loading
                ),
                localLoadStatesOf(
                    refreshLocal = NotLoading(endOfPaginationReached = false),
                    prependLocal = NotLoading(endOfPaginationReached = true)
                )
            ),
            differ.newCombinedLoadStates()
        )
        loadStateCallbacks.clear()
        differ.refresh()
        // after a refresh, make sure the loading event comes in 1 piece w/ the end of pagination
        // reset
        loadDispatcher.queue.removeLastOrNull()?.run()
        assertContentEquals(
            listOf(
                localLoadStatesOf(
                    refreshLocal = Loading,
                    prependLocal = NotLoading(endOfPaginationReached = false)
                ),
                localLoadStatesOf(
                    refreshLocal = NotLoading(endOfPaginationReached = false),
                    prependLocal = NotLoading(endOfPaginationReached = true)
                ),
            ),
            differ.newCombinedLoadStates()
        )
        assertContentEquals(
            listOf(
                localLoadStatesOf(
                    refreshLocal = Loading,
                    prependLocal = NotLoading(endOfPaginationReached = false)
                ),
                localLoadStatesOf(
                    refreshLocal = NotLoading(endOfPaginationReached = false),
                    prependLocal = NotLoading(endOfPaginationReached = true)
                ),
            ),
            loadStateCallbacks
        )
        collectLoadStates.cancel()
    }

    // TODO(b/195028524) the tests from here on checks the state after Invalid/Error results.
    //  Upon changes due to b/195028524, the asserts on these tests should see a new resetting
    //  LoadStateUpdate event

    @Test
    fun appendInvalid_loadStates() = params().forEach { appendInvalid_loadStates(it) }

    private fun appendInvalid_loadStates(collectWithCachedIn: Boolean) = runTest(collectWithCachedIn = collectWithCachedIn) { differ, loadDispatcher, pagingSources ->
        val collectLoadStates = differ.collectLoadStates()

        // initial REFRESH
        loadDispatcher.executeAll()

        assertContentEquals(0 until 9, differ.snapshot())
        assertContentEquals(
            listOf(
                localLoadStatesOf(refreshLocal = Loading),
                localLoadStatesOf(prependLocal = NotLoading.Complete),
            ),
            differ.newCombinedLoadStates()
        )

        // normal append
        differ[8]

        loadDispatcher.executeAll()

        assertContentEquals(0 until 12, differ.snapshot())
        assertContentEquals(
            listOf(
                localLoadStatesOf(
                    prependLocal = NotLoading.Complete,
                    appendLocal = Loading
                ),
                localLoadStatesOf(prependLocal = NotLoading.Complete)
            ),
            differ.newCombinedLoadStates()
        )

        // do invalid append which will return LoadResult.Invalid
        differ[11]
        pagingSources[0].nextLoadResult = LoadResult.Invalid()

        // using poll().run() instead of executeAll, otherwise this invalid APPEND + subsequent
        // REFRESH will auto run consecutively and we won't be able to assert them incrementally
        loadDispatcher.queue.removeLastOrNull()?.run()

        assertEquals(2, pagingSources.size)
        assertContentEquals(
            listOf(
                // the invalid append
                localLoadStatesOf(
                    prependLocal = NotLoading.Complete,
                    appendLocal = Loading
                ),
                // REFRESH on new paging source. Append/Prepend local states is reset because the
                // LoadStateUpdate from refresh sends the full map of a local LoadStates which was
                // initialized as IDLE upon new Snapshot.
                localLoadStatesOf(
                    refreshLocal = Loading,
                ),
            ),
            differ.newCombinedLoadStates()
        )

        // the LoadResult.Invalid from failed APPEND triggers new pagingSource + initial REFRESH
        loadDispatcher.queue.removeLastOrNull()?.run()

        assertContentEquals(11 until 20, differ.snapshot())
        assertContentEquals(
            listOf(localLoadStatesOf()),
            differ.newCombinedLoadStates()
        )

        collectLoadStates.cancel()
    }

    @Test
    fun prependInvalid_loadStates() = params().forEach { prependInvalid_loadStates(it) }

    private fun prependInvalid_loadStates(collectWithCachedIn: Boolean) = runTest(initialKey = 50, collectWithCachedIn = collectWithCachedIn) { differ, loadDispatcher,
        pagingSources ->
        val collectLoadStates = differ.collectLoadStates()

        // initial REFRESH
        loadDispatcher.executeAll()

        assertContentEquals(50 until 59, differ.snapshot())
        assertContentEquals(
            listOf(
                localLoadStatesOf(refreshLocal = Loading),
                // all local states NotLoading.Incomplete
                localLoadStatesOf(),
            ),
            differ.newCombinedLoadStates()
        )

        // normal prepend to ensure LoadStates for Page returns remains the same
        differ[0]

        loadDispatcher.executeAll()

        assertContentEquals(47 until 59, differ.snapshot())
        assertContentEquals(
            listOf(
                localLoadStatesOf(prependLocal = Loading),
                // all local states NotLoading.Incomplete
                localLoadStatesOf(),
            ),
            differ.newCombinedLoadStates()
        )

        // do an invalid prepend which will return LoadResult.Invalid
        differ[0]
        pagingSources[0].nextLoadResult = LoadResult.Invalid()
        loadDispatcher.queue.removeLastOrNull()?.run()

        assertEquals(2, pagingSources.size)
        assertContentEquals(
            listOf(
                // the invalid prepend
                localLoadStatesOf(prependLocal = Loading),
                // REFRESH on new paging source. Append/Prepend local states is reset because the
                // LoadStateUpdate from refresh sends the full map of a local LoadStates which was
                // initialized as IDLE upon new Snapshot.
                localLoadStatesOf(refreshLocal = Loading),
            ),
            differ.newCombinedLoadStates()
        )

        // the LoadResult.Invalid from failed PREPEND triggers new pagingSource + initial REFRESH
        loadDispatcher.queue.removeLastOrNull()?.run()

        // load starts from 0 again because the provided initialKey = 50 is not multi-generational
        assertContentEquals(0 until 9, differ.snapshot())
        assertContentEquals(
            listOf(localLoadStatesOf(prependLocal = NotLoading.Complete)),
            differ.newCombinedLoadStates()
        )

        collectLoadStates.cancel()
    }

    @Test
    fun refreshInvalid_loadStates() = params().forEach { refreshInvalid_loadStates(it) }

    private fun refreshInvalid_loadStates(collectWithCachedIn: Boolean) = runTest(initialKey = 50, collectWithCachedIn = collectWithCachedIn) { differ, loadDispatcher,
        pagingSources ->
        val collectLoadStates = differ.collectLoadStates()

        // execute queued initial REFRESH load which will return LoadResult.Invalid()
        pagingSources[0].nextLoadResult = LoadResult.Invalid()
        loadDispatcher.queue.removeLastOrNull()?.run()

        assertTrue(differ.snapshot().isEmpty())
        assertContentEquals(
            // invalid first refresh. The second refresh state update that follows is identical to
            // this LoadStates so it gets de-duped
            listOf(localLoadStatesOf(refreshLocal = Loading)),
            differ.newCombinedLoadStates()
        )

        // execute second REFRESH load
        loadDispatcher.queue.removeLastOrNull()?.run()

        // second refresh still loads from initialKey = 50 because anchorPosition/refreshKey is null
        assertEquals(2, pagingSources.size)
        assertContentEquals(50 until 59, differ.snapshot())
        assertContentEquals(
            // all local states NotLoading.Incomplete
            listOf(localLoadStatesOf()),
            differ.newCombinedLoadStates()
        )

        collectLoadStates.cancel()
    }

    @Test
    fun appendError_retryLoadStates() = params().forEach { appendError_retryLoadStates(it) }

    private fun appendError_retryLoadStates(collectWithCachedIn: Boolean) = runTest(collectWithCachedIn = collectWithCachedIn) { differ, loadDispatcher, pagingSources ->
        val collectLoadStates = differ.collectLoadStates()

        // initial REFRESH
        loadDispatcher.executeAll()

        assertContentEquals(
            listOf(
                localLoadStatesOf(refreshLocal = Loading),
                localLoadStatesOf(prependLocal = NotLoading.Complete),
            ),
            differ.newCombinedLoadStates()
        )
        assertContentEquals(0 until 9, differ.snapshot())

        // append returns LoadResult.Error
        differ[8]
        val exception = Throwable()
        pagingSources[0].nextLoadResult = LoadResult.Error(exception)

        loadDispatcher.queue.removeLastOrNull()?.run()

        assertContentEquals(
            listOf(
                localLoadStatesOf(
                    prependLocal = NotLoading.Complete,
                    appendLocal = Loading
                ),
                localLoadStatesOf(
                    prependLocal = NotLoading.Complete,
                    appendLocal = LoadState.Error(exception)
                ),
            ),
            differ.newCombinedLoadStates()
        )
        assertContentEquals(0 until 9, differ.snapshot())

        // retry append
        differ.retry()
        loadDispatcher.queue.removeLastOrNull()?.run()

        // make sure append success
        assertContentEquals(0 until 12, differ.snapshot())
        // no reset
        assertContentEquals(
            listOf(
                localLoadStatesOf(
                    prependLocal = NotLoading.Complete,
                    appendLocal = Loading
                ),
                localLoadStatesOf(prependLocal = NotLoading.Complete),
            ),
            differ.newCombinedLoadStates()
        )

        collectLoadStates.cancel()
    }

    @Test
    fun prependError_retryLoadStates() = params().forEach { prependError_retryLoadStates(it) }

    private fun prependError_retryLoadStates(collectWithCachedIn: Boolean) = runTest(initialKey = 50, collectWithCachedIn = collectWithCachedIn) { differ, loadDispatcher,
        pagingSources ->
        val collectLoadStates = differ.collectLoadStates()

        // initial REFRESH
        loadDispatcher.executeAll()

        assertContentEquals(
            listOf(
                localLoadStatesOf(refreshLocal = Loading),
                localLoadStatesOf(),
            ),
            differ.newCombinedLoadStates()
        )

        assertContentEquals(50 until 59, differ.snapshot())

        // prepend returns LoadResult.Error
        differ[0]
        val exception = Throwable()
        pagingSources[0].nextLoadResult = LoadResult.Error(exception)

        loadDispatcher.queue.removeLastOrNull()?.run()

        assertContentEquals(
            listOf(
                localLoadStatesOf(prependLocal = Loading),
                localLoadStatesOf(prependLocal = LoadState.Error(exception)),
            ),
            differ.newCombinedLoadStates()
        )
        assertContentEquals(50 until 59, differ.snapshot())

        // retry prepend
        differ.retry()

        loadDispatcher.queue.removeLastOrNull()?.run()

        // make sure prepend success
        assertContentEquals(47 until 59, differ.snapshot())
        assertContentEquals(
            listOf(
                localLoadStatesOf(prependLocal = Loading),
                localLoadStatesOf(),
            ),
            differ.newCombinedLoadStates()
        )

        collectLoadStates.cancel()
    }

    @Test
    fun refreshError_retryLoadStates() = params().forEach { refreshError_retryLoadStates(it) }

    private fun refreshError_retryLoadStates(collectWithCachedIn: Boolean) = runTest(collectWithCachedIn = collectWithCachedIn) { differ, loadDispatcher, pagingSources ->
        val collectLoadStates = differ.collectLoadStates()

        // initial load returns LoadResult.Error
        val exception = Throwable()
        pagingSources[0].nextLoadResult = LoadResult.Error(exception)

        loadDispatcher.executeAll()

        assertContentEquals(
            listOf(
                localLoadStatesOf(refreshLocal = Loading),
                localLoadStatesOf(refreshLocal = LoadState.Error(exception)),
            ),
            differ.newCombinedLoadStates()
        )
        assertTrue(differ.snapshot().isEmpty())

        // retry refresh
        differ.retry()

        loadDispatcher.queue.removeLastOrNull()?.run()

        // refresh retry does not trigger new gen
        assertContentEquals(0 until 9, differ.snapshot())
        // Goes directly from Error --> Loading without resetting refresh to NotLoading
        assertContentEquals(
            listOf(
                localLoadStatesOf(refreshLocal = Loading),
                localLoadStatesOf(prependLocal = NotLoading.Complete),
            ),
            differ.newCombinedLoadStates()
        )

        collectLoadStates.cancel()
    }

    @Test
    fun prependError_refreshLoadStates() = params().forEach { prependError_refreshLoadStates(it) }

    private fun prependError_refreshLoadStates(collectWithCachedIn: Boolean) = runTest(initialKey = 50, collectWithCachedIn = collectWithCachedIn) { differ, loadDispatcher,
        pagingSources ->
        val collectLoadStates = differ.collectLoadStates()

        // initial REFRESH
        loadDispatcher.executeAll()

        assertContentEquals(
            listOf(
                localLoadStatesOf(refreshLocal = Loading),
                localLoadStatesOf(),
            ),
            differ.newCombinedLoadStates()
        )
        assertEquals(9, differ.size)
        assertContentEquals(50 until 59, differ.snapshot())

        // prepend returns LoadResult.Error
        differ[0]
        val exception = Throwable()
        pagingSources[0].nextLoadResult = LoadResult.Error(exception)

        loadDispatcher.queue.removeLastOrNull()?.run()

        assertContentEquals(
            listOf(
                localLoadStatesOf(prependLocal = Loading),
                localLoadStatesOf(prependLocal = LoadState.Error(exception)),
            ),
            differ.newCombinedLoadStates()
        )

        // refresh() should reset local LoadStates and trigger new REFRESH
        differ.refresh()
        loadDispatcher.queue.removeLastOrNull()?.run()

        // Initial load starts from 0 because initialKey is single gen.
        assertContentEquals(0 until 9, differ.snapshot())
        assertContentEquals(
            listOf(
                // second gen REFRESH load. The Error prepend state was automatically reset to
                // NotLoading.
                localLoadStatesOf(refreshLocal = Loading),
                // REFRESH complete
                localLoadStatesOf(prependLocal = NotLoading.Complete),
            ),
            differ.newCombinedLoadStates()
        )

        collectLoadStates.cancel()
    }

    @Test
    fun refreshError_refreshLoadStates() = params().forEach { refreshError_refreshLoadStates(it) }

    private fun refreshError_refreshLoadStates(collectWithCachedIn: Boolean) = runTest(collectWithCachedIn = collectWithCachedIn) { differ, loadDispatcher, pagingSources ->
        val collectLoadStates = differ.collectLoadStates()

        // the initial load will return LoadResult.Error
        val exception = Throwable()
        pagingSources[0].nextLoadResult = LoadResult.Error(exception)

        loadDispatcher.executeAll()

        assertContentEquals(
            listOf(
                localLoadStatesOf(refreshLocal = Loading),
                localLoadStatesOf(refreshLocal = LoadState.Error(exception)),
            ),
            differ.newCombinedLoadStates()
        )
        assertTrue(differ.snapshot().isEmpty())

        // refresh should trigger new generation
        differ.refresh()

        loadDispatcher.queue.removeLastOrNull()?.run()

        assertContentEquals(0 until 9, differ.snapshot())
        // Goes directly from Error --> Loading without resetting refresh to NotLoading
        assertContentEquals(
            listOf(
                localLoadStatesOf(refreshLocal = Loading),
                localLoadStatesOf(prependLocal = NotLoading.Complete),
            ),
            differ.newCombinedLoadStates()
        )

        collectLoadStates.cancel()
    }

    @Test
    fun remoteRefresh_refreshStatePersists() = testScope.runTest {
        val differ = SimpleDiffer(dummyDifferCallback)
        val remoteMediator = RemoteMediatorMock(loadDelay = 1500).apply {
            initializeResult = RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH
        }
        val pager = Pager(
            PagingConfig(pageSize = 3, enablePlaceholders = false),
            remoteMediator = remoteMediator,
        ) {
            TestPagingSource(loadDelay = 500, items = emptyList())
        }

        val collectLoadStates = differ.collectLoadStates()
        val job = launch {
            pager.flow.collectLatest {
                differ.collectFrom(it)
            }
        }
        // allow local refresh to complete but not remote refresh
        advanceTimeBy(600)
        runCurrent()

        assertContentEquals(
            listOf(
                // local starts loading
                remoteLoadStatesOf(
                    refreshLocal = Loading,
                ),
                // remote starts loading
                remoteLoadStatesOf(
                    refresh = Loading,
                    refreshLocal = Loading,
                    refreshRemote = Loading,
                ),
                // local load returns with empty data, mediator is still loading
                remoteLoadStatesOf(
                    refresh = Loading,
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete,
                    refreshRemote = Loading,
                ),
            ),
            differ.newCombinedLoadStates()
        )

        // refresh triggers new generation & LoadState reset
        differ.refresh()

        // allow local refresh to complete but not remote refresh
        advanceTimeBy(600)
        runCurrent()

        assertContentEquals(
            listOf(
                // local starts second refresh while mediator continues remote refresh from before
                remoteLoadStatesOf(
                    refresh = Loading,
                    refreshLocal = Loading,
                    refreshRemote = Loading,
                ),
                // local load returns empty data
                remoteLoadStatesOf(
                    refresh = Loading,
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete,
                    refreshRemote = Loading,
                ),
            ),
            differ.newCombinedLoadStates()
        )

        // allow remote refresh to complete
        advanceTimeBy(600)
        runCurrent()

        assertContentEquals(
            // remote refresh returns empty and triggers remote append/prepend
            listOf(
                remoteLoadStatesOf(
                    prepend = Loading,
                    append = Loading,
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete,
                    prependRemote = Loading,
                    appendRemote = Loading,
                )
            ),
            differ.newCombinedLoadStates()
        )

        // allow remote append and prepend to complete
        advanceUntilIdle()

        assertContentEquals(
            // prepend completes first
            listOf(
                remoteLoadStatesOf(
                    append = Loading,
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete,
                    appendRemote = Loading,
                ),
                remoteLoadStatesOf(
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete,
                ),
            ),
            differ.newCombinedLoadStates()
        )

        job.cancel()
        collectLoadStates.cancel()
    }

    private fun runTest(
        scope: CoroutineScope = CoroutineScope(DirectDispatcher),
        differ: SimpleDiffer = SimpleDiffer(
            differCallback = dummyDifferCallback,
            coroutineScope = scope,
        ),
        loadDispatcher: TestDispatcher = TestDispatcher(),
        initialKey: Int? = null,
        pagingSources: MutableList<TestPagingSource> = mutableListOf(),
        pager: Pager<Int, Int> =
            Pager(
                config = PagingConfig(pageSize = 3, enablePlaceholders = false),
                initialKey = initialKey,
                pagingSourceFactory = {
                    TestPagingSource(
                        loadDelay = 0,
                        loadDispatcher = loadDispatcher,
                    ).also { pagingSources.add(it) }
                }
            ),
        collectWithCachedIn: Boolean,
        block: (SimpleDiffer, TestDispatcher, MutableList<TestPagingSource>) -> Unit
    ) {
        val collection = scope.launch {
            pager.flow.let {
                if (collectWithCachedIn) {
                    it.cachedIn(this)
                } else {
                    it
                }
            }.collect {
                differ.collectFrom(it)
            }
        }
        scope.run {
            try {
                block(differ, loadDispatcher, pagingSources)
            } finally {
                collection.cancel()
            }
        }
    }

    companion object {
        fun params() = arrayOf(true, false)
    }
}

private fun infinitelySuspendingPagingData(receiver: UiReceiver = dummyReceiver) = PagingData(
    flow { emit(suspendCancellableCoroutine<PageEvent<Int>> { }) },
    receiver
)

private class UiReceiverFake : UiReceiver {
    private val _hints = mutableListOf<ViewportHint>()
    val hints: List<ViewportHint>
        get() {
            val result = _hints.toList()
            @OptIn(ExperimentalStdlibApi::class)
            repeat(result.size) { _hints.removeFirst() }
            return result
        }

    val retryEvents = mutableListOf<Unit>()
    val refreshEvents = mutableListOf<Unit>()

    override fun accessHint(viewportHint: ViewportHint) {
        _hints.add(viewportHint)
    }

    override fun retry() {
        retryEvents.add(Unit)
    }

    override fun refresh() {
        refreshEvents.add(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class SimpleDiffer(
    differCallback: DifferCallback,
    val coroutineScope: CoroutineScope = TestScope(UnconfinedTestDispatcher())
) : PagingDataDiffer<Int>(differCallback) {
    override suspend fun presentNewList(
        previousList: NullPaddedList<Int>,
        newList: NullPaddedList<Int>,
        lastAccessedIndex: Int,
        onListPresentable: () -> Unit
    ): Int? {
        onListPresentable()
        return null
    }

    private val _localLoadStates = mutableListOf<CombinedLoadStates>()

    fun newCombinedLoadStates(): List<CombinedLoadStates?> {
        val newCombinedLoadStates = _localLoadStates.toList()
        _localLoadStates.clear()
        return newCombinedLoadStates
    }

    fun collectLoadStates(): Job {
        return coroutineScope.launch {
            loadStateFlow.collect { combinedLoadStates ->
                _localLoadStates.add(combinedLoadStates)
            }
        }
    }
}

internal val dummyReceiver = object : UiReceiver {
    override fun accessHint(viewportHint: ViewportHint) {}
    override fun retry() {}
    override fun refresh() {}
}

private val dummyDifferCallback = object : DifferCallback {
    override fun onInserted(position: Int, count: Int) {}

    override fun onChanged(position: Int, count: Int) {}

    override fun onRemoved(position: Int, count: Int) {}
}
