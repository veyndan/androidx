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
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class ConflatedEventBusTest {
    val testScope = TestScope()

    @Test
    fun noInitialValue() {
        val bus = ConflatedEventBus<Unit>(null)
        val collector = bus.createCollector().also {
            it.start()
        }
        testScope.runCurrent()
        assertTrue(collector.values.isEmpty())
        bus.send(Unit)
        testScope.runCurrent()
        assertContentEquals(listOf(Unit), collector.values)
    }

    @Test
    fun withInitialValue() {
        val bus = ConflatedEventBus<Int>(1)
        val collector = bus.createCollector().also {
            it.start()
        }
        testScope.runCurrent()
        assertContentEquals(listOf(1), collector.values)
        bus.send(2)
        testScope.runCurrent()
        assertContentEquals(listOf(1, 2), collector.values)
    }

    @Test
    fun allowDuplicateValues() {
        val bus = ConflatedEventBus<Int>(1)
        val collector = bus.createCollector().also {
            it.start()
        }
        testScope.runCurrent()
        assertContentEquals(listOf(1), collector.values)
        bus.send(1)
        testScope.runCurrent()
        assertContentEquals(listOf(1, 1), collector.values)
    }

    @Test
    fun conflateValues() {
        val bus = ConflatedEventBus<Int>(1)

        val collector = bus.createCollector()
        bus.send(2)
        collector.start()
        testScope.runCurrent()
        assertContentEquals(listOf(2), collector.values)
        bus.send(3)
        testScope.runCurrent()
        assertContentEquals(listOf(2, 3), collector.values)
    }

    @Test
    fun multipleCollectors() {
        val bus = ConflatedEventBus(1)
        val c1 = bus.createCollector().also {
            it.start()
        }
        testScope.runCurrent()
        bus.send(2)
        testScope.runCurrent()
        val c2 = bus.createCollector().also {
            it.start()
        }
        testScope.runCurrent()
        assertContentEquals(listOf(1, 2), c1.values)
        assertContentEquals(listOf(2), c2.values)
        bus.send(3)
        testScope.runCurrent()
        assertContentEquals(listOf(1, 2, 3), c1.values)
        assertContentEquals(listOf(2, 3), c2.values)
        bus.send(3)
        testScope.runCurrent()
        assertContentEquals(listOf(1, 2, 3, 3), c1.values)
        assertContentEquals(listOf(2, 3, 3), c2.values)
    }

    private fun <T : Any> ConflatedEventBus<T>.createCollector() = Collector(testScope, this)

    private class Collector<T : Any>(
        private val scope: CoroutineScope,
        private val bus: ConflatedEventBus<T>
    ) {
        private val _values = mutableListOf<T>()
        val values
            get() = _values

        fun start() {
            scope.launch {
                bus.flow.collect {
                    _values.add(it)
                }
            }
        }
    }
}
