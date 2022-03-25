/*
 * Copyright 2022 The Android Open Source Project
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
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleChannelFlowTest {
    val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun basic() = testScope.runTest {
        params().forEach { basic(it) }
    }

    private suspend fun basic(impl: Impl) {
        val channelFlow = createFlow<Int>(impl) {
            send(1)
            send(2)
        }
        val items = channelFlow.toList()
        assertContentEquals(listOf(1, 2), items)
    }

    @Test
    fun emitWithLaunch() = testScope.runTest {
        params().forEach { emitWithLaunch(it) }
    }

    private suspend fun emitWithLaunch(impl: Impl) {
        val channelFlow = createFlow<Int>(impl) {
            launch(coroutineContext, CoroutineStart.UNDISPATCHED) {
                send(1)
                delay(100)
                send(2)
            }
            send(3)
        }
        val items = channelFlow.toList()
        assertContentEquals(listOf(1, 3, 2), items)
    }

    @Test
    fun closedByCollector() = testScope.runTest {
        params().forEach { closedByCollector(it) }
    }

    private suspend fun closedByCollector(impl: Impl) {
        val emittedValues = mutableListOf<Int>()
        val channelFlow = createFlow<Int>(impl) {
            repeat(10) {
                send(it)
                emittedValues.add(it)
            }
        }
        assertContentEquals(listOf(0, 1, 2, 3), channelFlow.take(4).toList())
        assertContentEquals((0..9).toList(), emittedValues)
    }

    @Test
    fun closedByCollector_noBuffer() = testScope.runTest {
        params().forEach { closedByCollector_noBuffer(it) }
    }

    private suspend fun closedByCollector_noBuffer(impl: Impl) {
        val emittedValues = mutableListOf<Int>()
        val channelFlow = createFlow<Int>(impl) {
            repeat(10) {
                send(it)
                emittedValues.add(it)
            }
        }
        assertContentEquals(listOf(0, 1, 2, 3), channelFlow.buffer(0).take(4).toList())
        when (impl) {
            Impl.CHANNEL_FLOW -> {
                assertContentEquals(listOf(0, 1, 2, 3), emittedValues)
            }
            else -> {
                // simple channel flow cannot fuse properly, hence has an extra value
                assertContentEquals(listOf(0, 1, 2, 3, 4), emittedValues)
            }
        }
    }

    @Test
    fun awaitClose() = testScope.runTest {
        params().forEach { awaitClose(it) }
    }

    private suspend fun awaitClose(impl: Impl) {
        val lastDispatched = CompletableDeferred<Int>()
        val channelFlow = createFlow<Int>(impl) {
            var dispatched = -1
            launch {
                repeat(10) {
                    dispatched = it
                    send(it)
                    delay(100)
                }
            }
            awaitClose {
                assertTrue(lastDispatched.isActive)
                lastDispatched.complete(dispatched)
            }
        }
        channelFlow.takeWhile { it < 3 }.toList()
        assertEquals(3, lastDispatched.await())
    }

    @Test
    fun scopeGetsCancelled() = testScope.runTest {
        params().forEach { scopeGetsCancelled(it) }
    }

    private suspend fun scopeGetsCancelled(impl: Impl) {
        var producerException: Throwable? = null
        val dispatched = mutableListOf<Int>()
        val channelFlow = createFlow<Int>(impl) {
            try {
                repeat(20) {
                    send(it)
                    dispatched.add(it)
                    delay(100)
                }
            } catch (th: Throwable) {
                producerException = th
                throw th
            }
        }
        val collection = testScope.launch {
            channelFlow.toList()
        }
        testScope.advanceTimeBy(250)
        testScope.runCurrent()
        collection.cancel(CancellationException("test message"))
        collection.join()
        assertContentEquals(listOf(0, 1, 2), dispatched)
        assertContains(producerException!!.message!!, "test message")
    }

    @Test
    fun collectorThrows() = testScope.runTest {
        params().forEach { collectorThrows(it) }
    }

    private suspend fun collectorThrows(impl: Impl) {
        var producerException: Throwable? = null
        val channelFlow = createFlow<Int>(impl) {
            try {
                send(1)
                delay(1000)
                fail("should not arrive here")
            } catch (th: Throwable) {
                producerException = th
                throw th
            }
        }
        runCatching {
            channelFlow.collect {
                throw IllegalArgumentException("expected failure")
            }
        }
        assertContains(producerException!!.message!!, "consumer had failed")
    }

    @Test
    fun upstreamThrows() = testScope.runTest {
        params().forEach { upstreamThrows(it) }
    }

    private suspend fun upstreamThrows(impl: Impl) {
        var producerException: Throwable? = null
        val upstream = flow<Int> {
            emit(5)
            delay(100)
            emit(13)
        }
        val combinedFlow = upstream.flatMapLatest { upstreamValue ->
            createFlow<Int>(impl) {
                try {
                    send(upstreamValue)
                    delay(2000)
                    send(upstreamValue * 2)
                } catch (th: Throwable) {
                    if (producerException == null) {
                        producerException = th
                    }
                    throw th
                }
            }
        }
        assertContentEquals(
            listOf(5, 13, 26),
            combinedFlow.toList()
        )
        assertContains(producerException!!.message!!, "Child of the scoped flow was cancelled")
    }

    @Test
    fun cancelingChannelClosesTheFlow() = testScope.runTest {
        params().forEach { cancelingChannelClosesTheFlow(it) }
    }

    private suspend fun cancelingChannelClosesTheFlow(impl: Impl) {
        val flow = createFlow<Int>(impl) {
            send(1)
            close()
            awaitCancellation()
        }
        assertContentEquals(listOf(1), flow.toList())
    }

    private fun <T> createFlow(
        impl: Impl,
        block: suspend TestProducerScope<T>.() -> Unit
    ): Flow<T> {
        return when (impl) {
            Impl.CHANNEL_FLOW -> channelFlow {
                ChannelFlowTestProducerScope(this).block()
            }
            Impl.SIMPLE_CHANNEL_FLOW -> simpleChannelFlow {
                SimpleChannelFlowTestProducerScope(this).block()
            }
        }
    }

    // we want to run these tests with both channelFlow and simpleChannelFlow to check behavior
    // equality, hence the abstraction
    interface TestProducerScope<T> : CoroutineScope, SendChannel<T> {
        suspend fun awaitClose(block: () -> Unit)
    }

    internal class SimpleChannelFlowTestProducerScope<T>(
        private val delegate: SimpleProducerScope<T>
    ) : TestProducerScope<T>, CoroutineScope by delegate, SendChannel<T> by delegate {
        override suspend fun awaitClose(block: () -> Unit) {
            delegate.awaitClose(block)
        }
    }

    class ChannelFlowTestProducerScope<T>(
        private val delegate: ProducerScope<T>
    ) : TestProducerScope<T>, CoroutineScope by delegate, SendChannel<T> by delegate {
        override suspend fun awaitClose(block: () -> Unit) {
            delegate.awaitClose(block)
        }
    }

    companion object {
        fun params() = Impl.values()
    }

    enum class Impl {
        CHANNEL_FLOW,
        SIMPLE_CHANNEL_FLOW
    }
}