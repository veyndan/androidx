// /*
// * Copyright 2019 The Android Open Source Project
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *      http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
package androidx.paging

import androidx.paging.LoadState.Loading
import androidx.paging.LoadState.NotLoading
import androidx.paging.LoadType.APPEND
import androidx.paging.LoadType.REFRESH
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingSource.LoadResult.Page
import androidx.paging.RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH
import androidx.paging.RemoteMediator.InitializeAction.SKIP_INITIAL_REFRESH
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class PageFetcherTest {
    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val pagingSourceFactory = suspend { TestPagingSource() }
    private val config = PagingConfig(
        pageSize = 1,
        prefetchDistance = 1,
        enablePlaceholders = true,
        initialLoadSize = 2,
        maxSize = 3
    )

    @Test
    fun initialize() = testScope.runTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertEquals(1, fetcherState.pagingDataList.size)
        assertTrue { fetcherState.pageEventLists[0].isNotEmpty() }
        fetcherState.job.cancel()
    }

    @Test
    fun refresh() = testScope.runTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertEquals(1, fetcherState.pagingDataList.size)
        assertTrue { fetcherState.pageEventLists[0].isNotEmpty() }

        pageFetcher.refresh()
        advanceUntilIdle()

        assertEquals(2, fetcherState.pagingDataList.size)
        assertTrue { fetcherState.pageEventLists[1].isNotEmpty() }
        fetcherState.job.cancel()
    }

    @Test
    fun refresh_sourceEndOfPaginationReached() = testScope.runTest {
        @OptIn(ExperimentalPagingApi::class)
        val pageFetcher = PageFetcher(
            pagingSourceFactory = { TestPagingSource(items = emptyList()) },
            initialKey = 0,
            config = config,
        )
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertEquals(1, fetcherState.pagingDataList.size)
        assertTrue { fetcherState.pageEventLists[0].isNotEmpty() }

        pageFetcher.refresh()
        advanceUntilIdle()

        assertEquals(2, fetcherState.pagingDataList.size)
        assertTrue { fetcherState.pageEventLists[1].isNotEmpty() }
        fetcherState.job.cancel()
    }

    @Test
    fun refresh_remoteEndOfPaginationReached() = testScope.runTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = RemoteMediatorMock().apply {
            initializeResult = LAUNCH_INITIAL_REFRESH
            loadCallback = { _, _ ->
                RemoteMediator.MediatorResult.Success(endOfPaginationReached = true)
            }
        }
        val pageFetcher = PageFetcher(
            pagingSourceFactory = { TestPagingSource(items = emptyList()) },
            initialKey = 0,
            config = config,
            remoteMediator = remoteMediator
        )
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertEquals(1, fetcherState.pagingDataList.size)
        assertTrue { fetcherState.pageEventLists[0].isNotEmpty() }
        assertEquals(1, remoteMediator.loadEvents.size)

        pageFetcher.refresh()
        advanceUntilIdle()

        assertEquals(2, fetcherState.pagingDataList.size)
        assertTrue { fetcherState.pageEventLists[1].isNotEmpty() }
        assertEquals(2, remoteMediator.loadEvents.size)
        fetcherState.job.cancel()
    }

    @Test
    fun refresh_fromPagingSource() = testScope.runTest {
        var pagingSource: PagingSource<Int, Int>? = null
        val pagingSourceFactory = suspend {
            TestPagingSource().also { pagingSource = it }
        }
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertEquals(1, fetcherState.pagingDataList.size)
        assertTrue { fetcherState.pageEventLists[0].isNotEmpty() }

        val oldPagingSource = pagingSource
        oldPagingSource?.invalidate()
        advanceUntilIdle()

        assertEquals(2, fetcherState.pagingDataList.size)
        assertTrue { fetcherState.pageEventLists[1].isNotEmpty() }
        assertNotEquals(oldPagingSource, pagingSource)
        assertTrue { oldPagingSource!!.invalid }
        fetcherState.job.cancel()
    }

    @Test
    fun refresh_callsInvalidate() = testScope.runTest {
        var pagingSource: PagingSource<Int, Int>? = null
        val pagingSourceFactory = suspend {
            TestPagingSource().also { pagingSource = it }
        }
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        var didCallInvalidate = false
        pagingSource?.registerInvalidatedCallback { didCallInvalidate = true }

        advanceUntilIdle()

        assertEquals(1, fetcherState.pagingDataList.size)
        assertTrue { fetcherState.pageEventLists[0].isNotEmpty() }

        pageFetcher.refresh()
        advanceUntilIdle()

        assertEquals(2, fetcherState.pagingDataList.size)
        assertTrue { fetcherState.pageEventLists[1].isNotEmpty() }
        assertTrue { didCallInvalidate }
        fetcherState.job.cancel()
    }

    @Test
    fun refresh_invalidatePropagatesThroughLoadResultInvalid() =
        testScope.runTest {
            val pagingSources = mutableListOf<TestPagingSource>()
            val pageFetcher = PageFetcher(
                pagingSourceFactory = {
                    TestPagingSource().also {
                        // make this initial load return LoadResult.Invalid to see if new paging
                        // source is generated
                        if (pagingSources.size == 0) it.nextLoadResult = LoadResult.Invalid()
                        pagingSources.add(it)
                    }
                },
                initialKey = 50,
                config = config,
            )

            val fetcherState = collectFetcherState(pageFetcher)
            advanceUntilIdle()

            // should have two PagingData returned, one for each paging source
            assertEquals(2, fetcherState.pagingDataList.size)

            // First PagingData only returns a loading state because invalidation prevents load
            // completion
            assertTrue(pagingSources[0].invalid)
            assertContentEquals(
                listOf(localLoadStateUpdate<Int>(refreshLocal = Loading)),
                fetcherState.pageEventLists[0]
            )
            // previous load() returning LoadResult.Invalid should trigger a new paging source
            // retrying with the same load params, this should return a refresh starting
            // from key = 50
            assertTrue(!pagingSources[1].invalid)
            assertContentEquals(
                listOf(
                    localLoadStateUpdate<Int>(refreshLocal = Loading),
                    createRefresh(50..51)
                ),
                fetcherState.pageEventLists[1]
            )

            assertThat(pagingSources[0]).isNotEqualTo(pagingSources[1])
            fetcherState.job.cancel()
        }

    @Test
    fun append_invalidatePropagatesThroughLoadResultInvalid() =
        testScope.runTest {
            val pagingSources = mutableListOf<TestPagingSource>()
            val pageFetcher = PageFetcher(
                pagingSourceFactory = { TestPagingSource().also { pagingSources.add(it) } },
                initialKey = 50,
                config = config,
            )
            val fetcherState = collectFetcherState(pageFetcher)
            advanceUntilIdle()

            assertEquals(1, fetcherState.pageEventLists.size)
            assertContentEquals(
                listOf(
                    localLoadStateUpdate<Int>(refreshLocal = Loading),
                    createRefresh(50..51),
                ),
                fetcherState.newEvents()
            )

            // append a page
            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 1,
                    presentedItemsBefore = 1,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            // now return LoadResult.Invalid
            pagingSources[0].nextLoadResult = LoadResult.Invalid()

            advanceUntilIdle()

            // make sure the append load never completes
            assertEquals(
                localLoadStateUpdate<Int>(appendLocal = Loading),
                fetcherState.pageEventLists[0].last()
            )

            // the invalid result handler should exit the append load loop gracefully and allow
            // fetcher to generate a new paging source
            assertEquals(2, pagingSources.size)
            assertTrue(pagingSources[0].invalid)

            // second generation should load refresh with cached append load params
            assertContentEquals(
                listOf(
                    localLoadStateUpdate<Int>(refreshLocal = Loading),
                    createRefresh(51..52)
                ),
                fetcherState.newEvents()
            )

            fetcherState.job.cancel()
        }

    @Test
    fun prepend_invalidatePropagatesThroughLoadResultInvalid() =
        testScope.runTest {
            val pagingSources = mutableListOf<TestPagingSource>()
            val pageFetcher = PageFetcher(
                pagingSourceFactory = { TestPagingSource().also { pagingSources.add(it) } },
                initialKey = 50,
                config = config,
            )
            val fetcherState = collectFetcherState(pageFetcher)
            advanceUntilIdle()

            assertEquals(1, fetcherState.pageEventLists.size)
            assertContentEquals(
                listOf(
                    localLoadStateUpdate<Int>(refreshLocal = Loading),
                    createRefresh(50..51),
                ),
                fetcherState.newEvents()
            )
            // prepend a page
            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = -1,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 1,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            // now return LoadResult.Invalid
            pagingSources[0].nextLoadResult = LoadResult.Invalid()

            advanceUntilIdle()

            // make sure the prepend load never completes
            assertEquals(
                localLoadStateUpdate<Int>(prependLocal = Loading),
                fetcherState.pageEventLists[0].last()
            )

            // the invalid result should exit the prepend load loop gracefully and allow fetcher to
            // generate a new paging source
            assertEquals(2, pagingSources.size)
            assertTrue(pagingSources[0].invalid)

            // second generation should load refresh with cached prepend load params
            assertContentEquals(
                listOf(
                    localLoadStateUpdate<Int>(refreshLocal = Loading),
                    createRefresh(49..50)
                ),
                fetcherState.newEvents()
            )

            fetcherState.job.cancel()
        }

    @Test
    fun refresh_closesCollection() = testScope.runTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)

        var pagingDataCount = 0
        var didFinish = false
        val job = launch {
            pageFetcher.flow.collect { pagedData ->
                pagingDataCount++
                pagedData.flow
                    .onCompletion {
                        didFinish = true
                    }
                    // Return immediately to avoid blocking cancellation. This is analogous to
                    // logic which would process a single PageEvent and doesn't suspend
                    // indefinitely, which is what we expect to happen.
                    .collect { }
            }
        }

        advanceUntilIdle()

        pageFetcher.refresh()
        advanceUntilIdle()

        assertEquals(2, pagingDataCount)
        assertTrue { didFinish }
        job.cancel()
    }

    @Test
    fun refresh_closesUncollectedPageEventCh() = testScope.runTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)

        val pagingDatas = mutableListOf<PagingData<Int>>()
        val didFinish = mutableListOf<Boolean>()
        val job = launch {
            pageFetcher.flow.collectIndexed { index, pagingData ->
                pagingDatas.add(pagingData)
                if (index != 1) {
                    pagingData.flow
                        .onStart {
                            didFinish.add(false)
                        }
                        .onCompletion {
                            if (index < 2) didFinish[index] = true
                        }
                        // Return immediately to avoid blocking cancellation. This is analogous to
                        // logic which would process a single PageEvent and doesn't suspend
                        // indefinitely, which is what we expect to happen.
                        .collect { }
                }
            }
        }

        advanceUntilIdle()

        pageFetcher.refresh()
        pageFetcher.refresh()
        advanceUntilIdle()

        assertEquals(3, pagingDatas.size)
        withContext(coroutineContext) {
            // This should complete immediately without advanceUntilIdle().
            val deferred = async { pagingDatas[1].flow.collect { } }
            deferred.await()
        }

        assertEquals(listOf(true, false), didFinish)
        job.cancel()
    }

    @Test
    fun invalidate_unregistersListener() = testScope.runTest {
        var i = 0
        val pagingSources = mutableListOf<PagingSource<Int, Int>>()
        val pageFetcher = PageFetcher(
            pagingSourceFactory = {
                TestPagingSource().also {
                    pagingSources.add(it)

                    if (i == 0) {
                        // Force PageFetcher to create a second PagingSource before finding a
                        // valid one when instantiating first generation.
                        it.invalidate()
                    }
                    i++
                }
            },
            initialKey = 50,
            config = config
        )

        val state = collectFetcherState(pageFetcher)

        // Wait for first generation to instantiate.
        advanceUntilIdle()

        // Providing an invalid PagingSource should automatically trigger invalidation
        // regardless of when the invalidation callback is registered.
        assertEquals(2, pagingSources.size)

        // The first PagingSource is immediately invalid, so we shouldn't keep an invalidate
        // listener registered on it.
        assertEquals(0, pagingSources[0].invalidateCallbackCount)
        assertEquals(1, pagingSources[1].invalidateCallbackCount)

        // Trigger new generation, should unregister from older PagingSource.
        pageFetcher.refresh()
        advanceUntilIdle()
        assertEquals(3, pagingSources.size)
        assertEquals(0, pagingSources[1].invalidateCallbackCount)
        assertEquals(1, pagingSources[2].invalidateCallbackCount)

        state.job.cancel()
    }

    @Test
    fun collectTwice() = testScope.runTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)
        val fetcherState2 = collectFetcherState(pageFetcher)
        advanceUntilIdle()
        fetcherState.job.cancel()
        fetcherState2.job.cancel()
        advanceUntilIdle()
        assertEquals(1, fetcherState.pagingDataList.size)
        assertEquals(1, fetcherState2.pagingDataList.size)
        assertThat(fetcherState.pageEventLists.first()).isNotEmpty()
        assertThat(fetcherState2.pageEventLists.first()).isNotEmpty()
    }

    @Test
    fun remoteMediator_initializeSkip() = testScope.runTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediatorMock = RemoteMediatorMock().apply {
            initializeResult = SKIP_INITIAL_REFRESH
        }
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config, remoteMediatorMock)

        advanceUntilIdle()

        // Assert onInitialize is not called until collection.
        assertTrue { remoteMediatorMock.initializeEvents.isEmpty() }

        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertEquals(1, remoteMediatorMock.initializeEvents.size)
        assertEquals(0, remoteMediatorMock.loadEvents.size)

        fetcherState.job.cancel()
    }

    @Test
    fun remoteMediator_initializeLaunch() = testScope.runTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediatorMock = RemoteMediatorMock().apply {
            initializeResult = LAUNCH_INITIAL_REFRESH
        }
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config, remoteMediatorMock)

        advanceUntilIdle()

        // Assert onInitialize is not called until collection.
        assertTrue { remoteMediatorMock.initializeEvents.isEmpty() }

        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertEquals(1, remoteMediatorMock.initializeEvents.size)
        assertEquals(1, remoteMediatorMock.loadEvents.size)

        fetcherState.job.cancel()
    }

    @Test
    fun remoteMediator_load() = testScope.runTest {
        val remoteMediatorMock = RemoteMediatorMock()
        val pageFetcher = PageFetcher(pagingSourceFactory, 97, config, remoteMediatorMock)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        // Assert onBoundary is not called for non-terminal page load.
        assertTrue { remoteMediatorMock.loadEvents.isEmpty() }

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 1,
                presentedItemsBefore = 0,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )

        advanceUntilIdle()

        // Assert onBoundary is called for terminal page load.
        assertEquals(1, remoteMediatorMock.loadEvents.size)
        assertEquals(APPEND, remoteMediatorMock.loadEvents[0].loadType)

        fetcherState.job.cancel()
    }

    @Test
    fun jump() = testScope.runTest {
        withContext(coroutineContext) {
            val pagingSources = mutableListOf<PagingSource<Int, Int>>()
            val pagingSourceFactory = suspend {
                TestPagingSource().also { pagingSources.add(it) }
            }
            val config = PagingConfig(
                pageSize = 1,
                prefetchDistance = 1,
                enablePlaceholders = true,
                initialLoadSize = 2,
                maxSize = 3,
                jumpThreshold = 10
            )
            val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
            val fetcherState = collectFetcherState(pageFetcher)

            advanceUntilIdle()
            assertEquals(
                listOf<PageEvent<Int>>(
                    localLoadStateUpdate<Int>(refreshLocal = Loading),
                    createRefresh(range = 50..51)
                ),
                fetcherState.newEvents()
            )

            // Jump due to sufficiently large presentedItemsBefore
            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    // indexInPage value is incorrect, but should not be considered for jumps
                    indexInPage = 0,
                    presentedItemsBefore = -20,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()
            assertTrue { pagingSources[0].invalid }
            // Assert no new events added to current generation
            assertEquals(2, fetcherState.pageEventLists[0].size)
            assertEquals(
                listOf<PageEvent<Int>>(
                    localLoadStateUpdate<Int>(refreshLocal = Loading),
                    createRefresh(range = 50..51)
                ),
                fetcherState.newEvents()
            )

            // Jump due to sufficiently large presentedItemsAfter
            fetcherState.pagingDataList[1].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    // indexInPage value is incorrect, but should not be considered for jumps
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = -20,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()
            assertTrue { pagingSources[1].invalid }
            // Assert no new events added to current generation
            assertEquals(2, fetcherState.pageEventLists[1].size)
            assertEquals(
                listOf<PageEvent<Int>>(
                    localLoadStateUpdate<Int>(refreshLocal = Loading),
                    createRefresh(range = 50..51)
                ),
                fetcherState.newEvents()
            )

            fetcherState.job.cancel()
        }
    }

    @Test
    fun checksFactoryForNewInstance() = testScope.runTest {
        withContext(coroutineContext) {
            val pagingSource = TestPagingSource()
            val config = PagingConfig(
                pageSize = 1,
                prefetchDistance = 1,
                initialLoadSize = 2
            )
            val pageFetcher = PageFetcher(
                pagingSourceFactory = suspend { pagingSource },
                initialKey = 50,
                config = config
            )
            val job = testScope.launch {
                assertFailsWith<IllegalStateException> {
                    pageFetcher.flow.collect { }
                }
            }

            advanceUntilIdle()

            pageFetcher.refresh()
            advanceUntilIdle()

            assertTrue { job.isCompleted }
        }
    }

    @Test
    fun pagingSourceInvalidBeforeCallbackAdded() = testScope.runTest {
        var invalidatesFromAdapter = 0
        var i = 0
        var pagingSource: TestPagingSource? = null
        val pager = Pager(PagingConfig(10)) {
            i++
            TestPagingSource().also {
                if (i == 1) {
                    it.invalidate()
                }

                advanceUntilIdle()
                it.registerInvalidatedCallback { invalidatesFromAdapter++ }
                pagingSource = it
            }
        }

        @OptIn(ExperimentalStdlibApi::class)
        val job = launch {
            pager.flow.collectLatest { pagingData ->
                TestPagingDataDiffer<Int>(testScope.coroutineContext[CoroutineDispatcher]!!)
                    .collectFrom(pagingData)
            }
        }

        advanceUntilIdle()
        pagingSource!!.invalidate()
        advanceUntilIdle()

        // InvalidatedCallbacks added after a PagingSource is already invalid should be
        // immediately triggered, so both listeners we add should be triggered.
        assertEquals(2, invalidatesFromAdapter)
        job.cancel()
    }

    @Test
    fun pagingSourceInvalidBeforeCallbackAddedCancelsInitialLoad() = testScope.runTest {
        val pagingSources = mutableListOf<PagingSource<Int, Int>>()
        val loadedPages = mutableListOf<Page<Int, Int>>()

        var i = 0
        val pager = Pager(PagingConfig(10)) {
            object : PagingSource<Int, Int>() {
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
                    // Suspend and await advanceUntilIdle() before allowing load to complete.
                    delay(1000)
                    return Page.empty<Int, Int>().also {
                        loadedPages.add(it)
                    }
                }

                override fun getRefreshKey(state: PagingState<Int, Int>) = null
            }.also {
                pagingSources.add(it)

                if (i++ == 0) {
                    it.invalidate()
                }
            }
        }

        @OptIn(ExperimentalStdlibApi::class)
        val job = launch {
            pager.flow.collectLatest { pagingData ->
                TestPagingDataDiffer<Int>(testScope.coroutineContext[CoroutineDispatcher]!!)
                    .collectFrom(pagingData)
            }
        }

        // First PagingSource starts immediately invalid and creates a new PagingSource, but does
        // not finish initial page load.
        runCurrent()
        assertEquals(2, pagingSources.size)
        assertTrue(pagingSources[0].invalid)
        assertEquals(0, loadedPages.size)

        advanceUntilIdle()

        // After draining tasks, we should immediately get a second generation which triggers
        // page load, skipping the initial load from first generation due to cancellation.
        assertFalse(pagingSources[1].invalid)
        assertEquals(1, loadedPages.size)

        job.cancel()
    }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun cachesPreviousPagingStateOnEmptyPages() = testScope.runTest {
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 1,
            enablePlaceholders = true,
            initialLoadSize = 3,
        )

        val remoteMediator = RemoteMediatorMock().apply {
            initializeResult = LAUNCH_INITIAL_REFRESH
        }
        val pageFetcher = PageFetcher(
            pagingSourceFactory = suspend {
                TestPagingSource(loadDelay = 1000)
            },
            initialKey = 50,
            config = config,
            remoteMediator = remoteMediator,
        )

        var receiver: UiReceiver? = null
        val job = launch() {
            pageFetcher.flow.collectLatest {
                receiver = it.receiver
                it.flow.collect { }
            }
        }

        // Allow initial load to finish, so PagingState has non-zero pages.
        advanceUntilIdle()

        // Verify remote refresh is called with initial empty case.
        assertContentEquals<RemoteMediatorMock.LoadEvent<Int, Int>>(
            listOf(
                RemoteMediatorMock.LoadEvent(
                    loadType = REFRESH,
                    state = PagingState(
                        pages = listOf(),
                        anchorPosition = null,
                        config = config,
                        leadingPlaceholderCount = 0,
                    ),
                )
            ),
            remoteMediator.newLoadEvents
        )

        // Trigger refresh, instantiating second generation.
        pageFetcher.refresh()

        // Allow remote refresh to get triggered, but do not let paging source complete initial load
        // for second generation.
        advanceTimeBy(500)

        // Verify remote refresh is called with PagingState from first generation.
        val pagingState = PagingState(
            pages = listOf(
                Page(
                    data = listOf(50, 51, 52),
                    prevKey = 49,
                    nextKey = 53,
                    itemsBefore = 50,
                    itemsAfter = 47,
                )
            ),
            anchorPosition = null,
            config = config,
            leadingPlaceholderCount = 50,
        )
        assertContentEquals(
            listOf(RemoteMediatorMock.LoadEvent(loadType = REFRESH, state = pagingState)),
            remoteMediator.newLoadEvents
        )

        // Trigger a hint, which would normally populate anchorPosition. In real world scenario,
        // this would happen as a result of UI still presenting first generation since second
        // generation never finished loading yet.
        receiver?.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 2,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0,
            )
        )

        // Trigger refresh instantiating third generation before second has a chance to complete
        // initial load.
        pageFetcher.refresh()

        // Wait for all non-canceled loads to complete.
        advanceUntilIdle()

        // Verify remote refresh is called with PagingState from first generation, since second
        // generation never loaded any pages.
        assertContentEquals(
            listOf(RemoteMediatorMock.LoadEvent(loadType = REFRESH, state = pagingState)),
            remoteMediator.newLoadEvents
        )

        job.cancel()
    }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun cachesPreviousPagingStateOnNullHint() = testScope.runTest {
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 1,
            enablePlaceholders = true,
            initialLoadSize = 3,
        )

        val remoteMediator = RemoteMediatorMock().apply {
            initializeResult = LAUNCH_INITIAL_REFRESH
        }
        val pageFetcher = PageFetcher(
            pagingSourceFactory = suspend {
                TestPagingSource(loadDelay = 1000)
            },
            initialKey = 50,
            config = config,
            remoteMediator = remoteMediator,
        )

        var receiver: UiReceiver? = null
        val job = launch() {
            pageFetcher.flow.collectLatest {
                receiver = it.receiver
                it.flow.collect { }
            }
        }

        // Allow initial load to finish, so PagingState has non-zero pages.
        advanceUntilIdle()

        // Trigger a hint to populate anchorPosition, this should cause PageFetcher to cache this
        // PagingState and use it in next remoteRefresh
        receiver?.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 2,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0,
            )
        )

        // Verify remote refresh is called with initial empty case.
        assertContentEquals<RemoteMediatorMock.LoadEvent<Int, Int>>(
            listOf(
                RemoteMediatorMock.LoadEvent(
                    loadType = REFRESH,
                    state = PagingState(
                        pages = listOf(),
                        anchorPosition = null,
                        config = config,
                        leadingPlaceholderCount = 0,
                    ),
                )
            ),
            remoteMediator.newLoadEvents
        )

        // Trigger refresh, instantiating second generation.
        pageFetcher.refresh()

        // Allow remote refresh to get triggered, and let paging source load finish.
        advanceUntilIdle()

        // Verify remote refresh is called with PagingState from first generation.
        val pagingState = PagingState(
            pages = listOf(
                Page(
                    data = listOf(50, 51, 52),
                    prevKey = 49,
                    nextKey = 53,
                    itemsBefore = 50,
                    itemsAfter = 47,
                )
            ),
            anchorPosition = 50,
            config = config,
            leadingPlaceholderCount = 50,
        )
        assertContentEquals(
            listOf(RemoteMediatorMock.LoadEvent(loadType = REFRESH, state = pagingState)),
            remoteMediator.newLoadEvents
        )

        // Trigger refresh instantiating third generation before second has a chance to complete
        // initial load.
        pageFetcher.refresh()

        // Wait for all non-canceled loads to complete.
        advanceUntilIdle()

        // Verify remote refresh is called with PagingState from first generation, since second
        // generation never loaded any pages.
        assertContentEquals(
            listOf(RemoteMediatorMock.LoadEvent(loadType = REFRESH, state = pagingState)),
            remoteMediator.newLoadEvents
        )

        job.cancel()
    }

    @Test
    fun invalidateBeforeAccessPreservesPagingState() = testScope.runTest {
        withContext(coroutineContext) {
            val config = PagingConfig(
                pageSize = 1,
                prefetchDistance = 1,
                enablePlaceholders = true,
                initialLoadSize = 3,
            )
            val pagingSources = mutableListOf<TestPagingSource>()
            val pageFetcher = PageFetcher(
                pagingSourceFactory = suspend {
                    TestPagingSource(loadDelay = 1000).also {
                        pagingSources.add(it)
                    }
                },
                initialKey = 50,
                config = config,
            )

            lateinit var pagingData: PagingData<Int>
            val job = launch() {
                pageFetcher.flow.collectLatest {
                    pagingData = it
                    it.flow.collect { }
                }
            }

            advanceUntilIdle()

            // Trigger access to allow PagingState to get populated for next generation.
            pagingData.receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 1,
                    presentedItemsBefore = 1,
                    presentedItemsAfter = 1,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0,
                )
            )
            advanceUntilIdle()

            // Invalidate first generation, instantiating second generation.
            pagingSources[0].invalidate()

            // Invalidate second generation before it has a chance to complete initial load.
            advanceTimeBy(500)
            pagingSources[1].invalidate()

            // Wait for all non-canceled loads to complete.
            advanceUntilIdle()

            // Verify 3 generations were instantiated.
            assertEquals(3, pagingSources.size)

            // First generation should use initialKey.
            assertTrue(pagingSources[0].getRefreshKeyCalls.isEmpty())

            // Second generation should receive getRefreshKey call with state from first generation.
            assertEquals(
                listOf(
                    PagingState(
                        pages = pagingSources[0].loadedPages,
                        anchorPosition = 51,
                        config = config,
                        leadingPlaceholderCount = 50,
                    )
                ),
                pagingSources[1].getRefreshKeyCalls
            )

            // Verify second generation was invalidated before any pages loaded.
            assertTrue(pagingSources[1].loadedPages.isEmpty())

            // Third generation should receive getRefreshKey call with state from first generation.
            assertEquals(1, pagingSources[0].loadedPages.size)
            assertEquals(
                listOf(
                    PagingState(
                        pages = pagingSources[0].loadedPages,
                        anchorPosition = 51,
                        config = config,
                        leadingPlaceholderCount = 50,
                    )
                ),
                pagingSources[2].getRefreshKeyCalls
            )

            advanceUntilIdle()
            // Trigger APPEND in third generation.
            pagingData.receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 2,
                    presentedItemsBefore = 2,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0,
                )
            )
            advanceUntilIdle()

            // Invalidate third generation, instantiating fourth generation with new PagingState.
            pagingSources[2].invalidate()
            advanceUntilIdle()

            // Fourth generation should receive getRefreshKey call with state from third generation.
            assertEquals(2, pagingSources[2].loadedPages.size)
            assertEquals(
                listOf(
                    PagingState(
                        pages = pagingSources[2].loadedPages,
                        anchorPosition = 53,
                        config = config,
                        leadingPlaceholderCount = 51,
                    )
                ),
                pagingSources[3].getRefreshKeyCalls
            )

            job.cancel()
        }
    }

    @Test
    fun refresh_sourceEndOfPaginationReached_loadStates() = testScope.runTest {
        @OptIn(ExperimentalPagingApi::class)
        val pageFetcher = PageFetcher(
            pagingSourceFactory = { TestPagingSource(items = emptyList()) },
            initialKey = 0,
            config = config,
        )
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertEquals(1, fetcherState.pagingDataList.size)
        assertContentEquals(
            listOf(
                localLoadStateUpdate<Int>(
                    refreshLocal = Loading,
                ),
                EMPTY_SOURCE_REFRESH,
            ),
            fetcherState.newEvents()
        )

        pageFetcher.refresh()
        advanceUntilIdle()

        assertEquals(2, fetcherState.pagingDataList.size)
        assertContentEquals(
            listOf(
                localLoadStateUpdate<Int>(
                    refreshLocal = Loading,
                ),
                EMPTY_SOURCE_REFRESH,
            ),
            fetcherState.newEvents()
        )
        fetcherState.job.cancel()
    }

    @Test
    fun refresh_remoteEndOfPaginationReached_loadStates() = testScope.runTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = RemoteMediatorMock().apply {
            initializeResult = LAUNCH_INITIAL_REFRESH
            loadCallback = { _, _ ->
                // Wait for advanceUntilIdle()
                delay(1)
                RemoteMediator.MediatorResult.Success(endOfPaginationReached = true)
            }
        }
        val pageFetcher = PageFetcher(
            pagingSourceFactory = { TestPagingSource(items = emptyList()) },
            initialKey = 0,
            config = config,
            remoteMediator = remoteMediator
        )
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertContentEquals(
            listOf(
                remoteLoadStateUpdate<Int>(
                    refreshLocal = Loading,
                ),
                remoteLoadStateUpdate<Int>(
                    refreshLocal = Loading,
                    refreshRemote = Loading,
                ),
                // all remote States should be updated within single LoadStateUpdate
                remoteLoadStateUpdate<Int>(
                    refreshLocal = Loading,
                    refreshRemote = NotLoading.Incomplete,
                    prependRemote = NotLoading.Complete,
                    appendRemote = NotLoading.Complete,
                ),
                EMPTY_REMOTE_REFRESH,
            ),
            fetcherState.newEvents()
        )

        pageFetcher.refresh()
        advanceUntilIdle()

        assertContentEquals(
            listOf(
                // Remote state carried over from previous generation.
                remoteLoadStateUpdate<Int>(
                    refreshLocal = Loading,
                    refreshRemote = NotLoading.Incomplete,
                    prependRemote = NotLoading.Complete,
                    appendRemote = NotLoading.Complete,
                ),
                remoteLoadStateUpdate<Int>(
                    refreshLocal = Loading,
                    refreshRemote = Loading,
                    prependRemote = NotLoading.Complete,
                    appendRemote = NotLoading.Complete,
                ),
                remoteLoadStateUpdate<Int>(
                    refreshLocal = Loading,
                    refreshRemote = NotLoading.Incomplete,
                    prependRemote = NotLoading.Complete,
                    appendRemote = NotLoading.Complete,
                ),
                EMPTY_REMOTE_REFRESH,
            ),
            fetcherState.newEvents()
        )

        fetcherState.job.cancel()
    }

    /**
     * Check that rapid remote events are not dropped and don't cause redundant events.
     */
    @Test
    fun injectRemoteEvents_fastRemoteEvents() = testScope.runTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = RemoteMediatorMock().apply {
            initializeResult = LAUNCH_INITIAL_REFRESH
            loadCallback = { _, _ ->
                RemoteMediator.MediatorResult.Success(endOfPaginationReached = true)
            }
        }
        val pageFetcher = PageFetcher(
            pagingSourceFactory = { TestPagingSource(items = emptyList()) },
            initialKey = 0,
            config = config,
            remoteMediator = remoteMediator
        )
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertContentEquals(
            listOf(
                remoteLoadStateUpdate<Int>(
                    refreshLocal = Loading,
                    refreshRemote = NotLoading.Incomplete,
                ),
                remoteLoadStateUpdate<Int>(
                    refreshLocal = Loading,
                    refreshRemote = Loading,
                ),
                remoteLoadStateUpdate<Int>(
                    refreshLocal = Loading,
                    refreshRemote = NotLoading.Incomplete,
                    prependRemote = NotLoading.Complete,
                    appendRemote = NotLoading.Complete,
                ),
                remoteRefresh<Int>(
                    source = loadStates(
                        append = NotLoading.Complete,
                        prepend = NotLoading.Complete,
                    ),
                    mediator = loadStates(
                        refresh = NotLoading.Incomplete,
                        append = NotLoading.Complete,
                        prepend = NotLoading.Complete,
                    ),
                ),
            ),
            fetcherState.newEvents()
        )
        fetcherState.job.cancel()
    }

    @Suppress("DEPRECATION") // b/220884819
    @Test
    fun injectRemoteEvents_remoteLoadAcrossGenerations() = runBlockingTest {
        val neverEmitCh = Channel<Int>()
        var generation = 0

        @OptIn(ExperimentalPagingApi::class)
        val pageFetcher = PageFetcher(
            pagingSourceFactory = {
                generation++
                object : PagingSource<Int, Int>() {
                    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
                        // Wait for advanceUntilIdle()
                        delay(1)

                        return when (generation) {
                            1 -> Page(
                                data = listOf(),
                                prevKey = null,
                                nextKey = null
                            )
                            else -> Page(
                                data = listOf(3, 4, 5),
                                prevKey = 2,
                                nextKey = 6
                            )
                        }
                    }

                    override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
                }
            },
            initialKey = 0,
            config = config,
            remoteMediator = object : RemoteMediator<Int, Int>() {
                override suspend fun initialize(): InitializeAction = SKIP_INITIAL_REFRESH

                override suspend fun load(
                    loadType: LoadType,
                    state: PagingState<Int, Int>
                ): MediatorResult {
                    // Wait for advanceUntilIdle()
                    delay(1)

                    if (loadType == REFRESH) {
                        return MediatorResult.Success(endOfPaginationReached = false)
                    }

                    neverEmitCh.receiveCatching()
                    return MediatorResult.Error(Exception("Unexpected"))
                }
            }
        )
        val fetcherState = collectFetcherState(pageFetcher)

        assertContentEquals(
            listOf(
                remoteLoadStateUpdate<Int>(
                    source = loadStates(
                        refresh = Loading,
                    ),
                )
            ),
            fetcherState.newEvents()
        )

        // Let initial source refresh complete and kick off remote prepend / append.
        advanceUntilIdle()

        // First generation loads empty list and triggers remote loads.
        assertContentEquals(
            listOf(
                remoteRefresh(
                    pages = listOf(
                        TransformablePage(data = listOf())
                    ),
                    source = loadStates(
                        prepend = NotLoading.Complete,
                        append = NotLoading.Complete,
                    ),
                ),
                remoteLoadStateUpdate<Int>(
                    source = loadStates(
                        prepend = NotLoading.Complete,
                        append = NotLoading.Complete,
                    ),
                    mediator = loadStates(
                        prepend = Loading,
                    ),
                ),
                remoteLoadStateUpdate<Int>(
                    source = loadStates(
                        prepend = NotLoading.Complete,
                        append = NotLoading.Complete,
                    ),
                    mediator = loadStates(
                        prepend = Loading,
                        append = Loading,
                    ),
                ),
            ),
            fetcherState.newEvents()
        )

        // Trigger remote + source refresh in a new generation.
        pageFetcher.refresh()

        assertContentEquals(
            listOf(
                remoteLoadStateUpdate<Int>(
                    source = loadStates(refresh = Loading),
                    mediator = loadStates(
                        prepend = Loading,
                        append = Loading,
                    ),
                )
            ),
            fetcherState.newEvents()
        )

        // Let remote and source refresh finish.
        advanceUntilIdle()

        // Second generation loads some data and has more to load from source.
        assertContentEquals(
            listOf(
                remoteLoadStateUpdate<Int>(
                    source = loadStates(refresh = Loading),
                    mediator = loadStates(
                        refresh = Loading,
                        prepend = Loading,
                        append = Loading,
                    ),
                ),
                remoteLoadStateUpdate<Int>(
                    source = loadStates(refresh = Loading),
                    mediator = loadStates(
                        prepend = Loading,
                        append = Loading,
                    )
                ),
                remoteRefresh(
                    pages = listOf(TransformablePage(data = listOf(3, 4, 5))),
                    mediator = loadStates(
                        prepend = Loading,
                        append = Loading,
                    ),
                ),
            ),
            fetcherState.newEvents()
        )

        // Trigger remote + source refresh in a third generation.
        pageFetcher.refresh()

        // Start of third generation should have the exact same load states as before, so we
        // should only get new events for kicking off new loads.
        assertContentEquals(
            listOf(
                remoteLoadStateUpdate<Int>(
                    source = loadStates(refresh = Loading),
                    mediator = loadStates(
                        prepend = Loading,
                        append = Loading,
                    ),
                ),
            ),
            fetcherState.newEvents()
        )

        // Let remote and source refresh finish.
        advanceUntilIdle()

        assertContentEquals(
            listOf(
                remoteLoadStateUpdate<Int>(
                    source = loadStates(refresh = Loading),
                    mediator = loadStates(
                        refresh = Loading,
                        prepend = Loading,
                        append = Loading,
                    ),
                ),
                remoteLoadStateUpdate<Int>(
                    source = loadStates(refresh = Loading),
                    mediator = loadStates(
                        prepend = Loading,
                        append = Loading,
                    )
                ),
                remoteRefresh(
                    pages = listOf(TransformablePage(data = listOf(3, 4, 5))),
                    mediator = loadStates(
                        prepend = Loading,
                        append = Loading,
                    ),
                ),
            ),
            fetcherState.newEvents()
        )

        neverEmitCh.close()
        fetcherState.job.cancel()
    }

    @Test
    fun injectRemoteEvents_doesNotKeepOldGenerationActive() = testScope.runTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = RemoteMediatorMock().apply {
            initializeResult = LAUNCH_INITIAL_REFRESH
            loadCallback = { _, _ ->
                RemoteMediator.MediatorResult.Success(endOfPaginationReached = true)
            }
        }
        val pageFetcher = PageFetcher(
            pagingSourceFactory = { TestPagingSource(items = emptyList()) },
            initialKey = 0,
            config = config,
            remoteMediator = remoteMediator
        )
        val fetcherState = collectFetcherState(pageFetcher)
        advanceUntilIdle()

        // Clear out all events.
        val firstGenerationEventCount = fetcherState.pageEventLists[0].size

        // Let new generation and some new remote events emit.
        pageFetcher.refresh()
        advanceUntilIdle()

        assertEquals(fetcherState.pageEventLists[0].size, firstGenerationEventCount)

        fetcherState.job.cancel()
    }

    /**
     * See b/183345509
     */
    @Test
    fun remoteRefreshTriggeredDespiteImmediateInvalidation() = testScope.runTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = RemoteMediatorMock().apply {
            initializeResult = LAUNCH_INITIAL_REFRESH
            loadCallback = { _, _ ->
                RemoteMediator.MediatorResult.Success(endOfPaginationReached = true)
            }
        }
        var generation = 0
        val pageFetcher = PageFetcher(
            pagingSourceFactory = {
                TestPagingSource(items = emptyList()).also {
                    if (generation++ == 0) it.invalidate()
                }
            },
            initialKey = 0,
            config = config,
            remoteMediator = remoteMediator
        )

        val fetcherState = collectFetcherState(pageFetcher)
        advanceUntilIdle()

        assertThat(remoteMediator.newLoadEvents).isNotEmpty()
        fetcherState.job.cancel()
    }

    companion object {
        internal val EMPTY_SOURCE_REFRESH =
            localRefresh<Int>(
                source = loadStates(
                    prepend = NotLoading.Complete,
                    append = NotLoading.Complete,
                )
            )

        internal val EMPTY_REMOTE_REFRESH =
            remoteRefresh<Int>(
                source = loadStates(
                    append = NotLoading.Complete,
                    prepend = NotLoading.Complete,
                ),
                mediator = loadStates(
                    refresh = NotLoading.Incomplete,
                    append = NotLoading.Complete,
                    prepend = NotLoading.Complete,
                ),
            )
    }
}

internal class FetcherState<T : Any>(
    val pagingDataList: ArrayList<PagingData<T>>,
    val pageEventLists: ArrayList<ArrayList<PageEvent<T>>>,
    val job: Job
) {
    private var lastPageEventListIndex = -1
    var lastIndex = -1

    fun newEvents(): List<PageEvent<T>> {
        if (lastPageEventListIndex != pageEventLists.lastIndex) {
            lastPageEventListIndex = pageEventLists.lastIndex
            lastIndex = -1
        }

        val pageEvents = pageEventLists.lastOrNull()?.toMutableList() ?: listOf()
        return pageEvents.drop(lastIndex + 1).also {
            lastIndex = pageEvents.lastIndex
        }
    }
}

internal fun CoroutineScope.collectFetcherState(fetcher: PageFetcher<Int, Int>): FetcherState<Int> {
    val pagingDataList: ArrayList<PagingData<Int>> = ArrayList()
    val pageEventLists: ArrayList<ArrayList<PageEvent<Int>>> = ArrayList()

    val job = launch {
        fetcher.flow.collectIndexed { index, pagingData ->
            pagingDataList.add(index, pagingData)
            pageEventLists.add(index, ArrayList())
            launch {
                pagingData.flow.toList(pageEventLists[index])
            }
        }
    }

    return FetcherState(pagingDataList, pageEventLists, job)
}
