/*
 * Copyright 2019 The Android Open Source Project
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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CachedPageEventFlowTest {
    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun slowFastCollectors() = testScope.runTest {
        params().forEach { slowFastCollectors(it) }
    }

    private suspend fun slowFastCollectors(terminationType: TerminationType) {
        val upstream = Channel<PageEvent<String>>(Channel.UNLIMITED)
        val subject = CachedPageEventFlow(
            src = upstream.consumeAsFlow(),
            scope = testScope
        )
        val fastCollector = PageCollector(subject.downstreamFlow)
        fastCollector.collectIn(testScope)
        val slowCollector = PageCollector(
            subject.downstreamFlow.onEach {
                delay(1_000)
            }
        )
        slowCollector.collectIn(testScope)
        val refreshEvent = localRefresh(
            listOf(
                TransformablePage(
                    listOf("a", "b", "c")
                )
            ),
        )
        upstream.send(refreshEvent)
        testScope.runCurrent()
        assertContentEquals(
            listOf(refreshEvent),
            fastCollector.items()
        )
        assertTrue(slowCollector.items().isEmpty())

        val appendEvent = localAppend(
            listOf(
                TransformablePage(
                    listOf("d", "e")
                )
            ),
        )
        upstream.send(appendEvent)
        testScope.runCurrent()
        assertContentEquals(
            listOf(
                refreshEvent,
                appendEvent
            ),
            fastCollector.items()
        )
        assertTrue(slowCollector.items().isEmpty())
        testScope.advanceTimeBy(3_000)
        assertContentEquals(
            listOf(
                refreshEvent,
                appendEvent
            ),
            slowCollector.items()
        )
        val manyNewAppendEvents = (0 until 100).map {
            localAppend(
                listOf(
                    TransformablePage(
                        listOf("f", "g")
                    )
                ),
            )
        }
        manyNewAppendEvents.forEach {
            upstream.send(it)
        }
        val lateSlowCollector = PageCollector(subject.downstreamFlow.onEach { delay(1_000) })
        lateSlowCollector.collectIn(testScope)
        val finalAppendEvent = localAppend(
            listOf(
                TransformablePage(
                    listOf("d", "e")
                )
            ),
        )
        upstream.send(finalAppendEvent)
        when (terminationType) {
            TerminationType.CLOSE_UPSTREAM -> upstream.close()
            TerminationType.CLOSE_CACHED_EVENT_FLOW -> subject.close()
        }
        val fullList = listOf(
            refreshEvent,
            appendEvent
        ) + manyNewAppendEvents + finalAppendEvent
        testScope.runCurrent()
        assertContentEquals(fullList, fastCollector.items())
        assertFalse(fastCollector.isActive())
        assertTrue(slowCollector.isActive())
        assertTrue(lateSlowCollector.isActive())
        testScope.advanceUntilIdle()
        assertContentEquals(fullList, slowCollector.items())
        assertFalse(slowCollector.isActive())

        val lateCollectorState = localRefresh(
            pages = (listOf(refreshEvent, appendEvent) + manyNewAppendEvents).flatMap {
                it.pages
            },
        )
        assertContentEquals(
            listOf(lateCollectorState, finalAppendEvent),
            lateSlowCollector.items()
        )
        assertFalse(lateSlowCollector.isActive())

        upstream.close()
    }

    @Test
    fun ensureSharing() = testScope.runTest {
        params().forEach { ensureSharing(it) }
    }

    private suspend fun ensureSharing(terminationType: TerminationType) {
        val refreshEvent = localRefresh(
            listOf(
                TransformablePage(
                    listOf("a", "b", "c")
                )
            ),
        )
        val appendEvent = localAppend(
            listOf(
                TransformablePage(
                    listOf("d", "e")
                )
            ),
        )
        val upstream = Channel<PageEvent<String>>(Channel.UNLIMITED)
        val subject = CachedPageEventFlow(
            src = upstream.consumeAsFlow(),
            scope = testScope
        )

        val collector1 = PageCollector(subject.downstreamFlow)
        upstream.send(refreshEvent)
        upstream.send(appendEvent)
        collector1.collectIn(testScope)
        testScope.runCurrent()
        assertEquals(
            listOf(refreshEvent, appendEvent),
            collector1.items()
        )
        val collector2 = PageCollector(subject.downstreamFlow)
        collector2.collectIn(testScope)
        testScope.runCurrent()
        val firstSnapshotRefreshEvent = localRefresh(
            listOf(
                TransformablePage(
                    listOf("a", "b", "c")
                ),
                TransformablePage(
                    listOf("d", "e")
                )
            ),
        )
        assertContentEquals(listOf(firstSnapshotRefreshEvent), collector2.items())
        val prependEvent = localPrepend(
            listOf(
                TransformablePage(
                    listOf("a0", "a1")
                ),
                TransformablePage(
                    listOf("a2", "a3")
                )
            ),
        )
        upstream.send(prependEvent)
        assertEquals(
            listOf(refreshEvent, appendEvent, prependEvent),
            collector1.items()
        )
        assertEquals(
            listOf(firstSnapshotRefreshEvent, prependEvent),
            collector2.items()
        )
        val collector3 = PageCollector(subject.downstreamFlow)
        collector3.collectIn(testScope)
        val finalState = localRefresh(
            listOf(
                TransformablePage(
                    listOf("a0", "a1")
                ),
                TransformablePage(
                    listOf("a2", "a3")
                ),
                TransformablePage(
                    listOf("a", "b", "c")
                ),
                TransformablePage(
                    listOf("d", "e")
                )
            ),
        )
        assertContentEquals(
            listOf(finalState),
            collector3.items()
        )
        assertTrue(collector1.isActive())
        assertTrue(collector2.isActive())
        assertTrue(collector3.isActive())
        when (terminationType) {
            TerminationType.CLOSE_UPSTREAM -> upstream.close()
            TerminationType.CLOSE_CACHED_EVENT_FLOW -> subject.close()
        }
        testScope.runCurrent()
        assertFalse(collector1.isActive())
        assertFalse(collector2.isActive())
        assertFalse(collector3.isActive())
        val collector4 = PageCollector(subject.downstreamFlow).also {
            it.collectIn(testScope)
        }
        testScope.runCurrent()
        // since upstream is closed, this should just close
        assertFalse(collector4.isActive())
        assertContentEquals(
            listOf(finalState),
            collector4.items()
        )
    }

    @Test
    fun emptyPage_singlelocalLoadStateUpdate() = testScope.runTest {
        val upstream = Channel<PageEvent<String>>(Channel.UNLIMITED)
        val subject = CachedPageEventFlow(
            src = upstream.consumeAsFlow(),
            scope = testScope
        )

        // creating two collectors and collecting right away to assert that all collectors
        val collector = PageCollector(subject.downstreamFlow)
        collector.collectIn(testScope)

        val collector2 = PageCollector(subject.downstreamFlow)
        collector2.collectIn(testScope)

        runCurrent()

        // until upstream sends events, collectors shouldn't receive any events
        assertTrue(collector.items().isEmpty())
        assertTrue(collector2.items().isEmpty())

        // now send refresh event
        val refreshEvent = localRefresh(
            listOf(
                TransformablePage(
                    listOf("a", "b", "c")
                )
            ),
        )
        upstream.send(refreshEvent)
        runCurrent()

        assertContentEquals(
            listOf(refreshEvent),
            collector.items()
        )

        assertContentEquals(
            listOf(refreshEvent),
            collector2.items()
        )

        upstream.close()
    }

    @Test
    fun idleStateUpdate_collectedBySingleCollector() = testScope.runTest {
        val upstream = Channel<PageEvent<String>>(Channel.UNLIMITED)
        val subject = CachedPageEventFlow(
            src = upstream.consumeAsFlow(),
            scope = testScope
        )

        val refreshEvent = localRefresh(
            listOf(
                TransformablePage(
                    listOf("a", "b", "c")
                )
            ),
        )
        upstream.send(refreshEvent)
        runCurrent()

        val collector = PageCollector(subject.downstreamFlow)
        collector.collectIn(testScope)

        runCurrent()

        // collector shouldn't receive any idle events before the refresh
        assertContentEquals(
            listOf(refreshEvent),
            collector.items()
        )

        val delayedCollector = PageCollector(subject.downstreamFlow)
        delayedCollector.collectIn(testScope)

        // delayed collector shouldn't receive any idle events since we already have refresh
        assertContentEquals(
            listOf(refreshEvent),
            delayedCollector.items()
        )

        upstream.close()
    }

    private class PageCollector<T : Any>(val src: Flow<T>) {
        private val items = mutableListOf<T>()
        private var job: Job? = null
        fun collectIn(scope: CoroutineScope) {
            job = scope.launch {
                src.collect {
                    items.add(it)
                }
            }
        }

        fun isActive() = job?.isActive ?: false
        fun items() = items.toList()
    }

    companion object {
        fun params() = TerminationType.values()
    }

    enum class TerminationType {
        CLOSE_UPSTREAM,
        CLOSE_CACHED_EVENT_FLOW
    }
}