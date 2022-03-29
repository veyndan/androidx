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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleTransformLatestTest {
    private val testScope = TestScope()

    @Test
    fun delayed() = testScope.runTest {
        params().forEach { delayed(it) }
    }

    private suspend fun delayed(impl: Impl) {
        assertContentEquals(
            listOf(
                "1 - 0", "1 - 1",
                "2 - 0", "2 - 1",
                "3 - 0", "3 - 1", "3 - 2"
            ),
            flowOf(1, 2, 3)
                .onEach { delay(100) }
                .testTransformLatest<Int, String>(impl) { value ->
                    repeat(3) {
                        emit("$value - $it")
                        delay(75)
                    }
                }.toList()
        )
    }

    @Test
    fun allValues() = testScope.runTest {
        params().forEach { allValues(it) }
    }

    private suspend fun allValues(impl: Impl) {
        assertContentEquals(
            listOf(
                "1 - 0", "1 - 1", "1 - 2",
                "2 - 0", "2 - 1", "2 - 2",
                "3 - 0", "3 - 1", "3 - 2"
            ),
            flowOf(1, 2, 3)
                .onEach { delay(1) }
                .testTransformLatest<Int, String>(impl) { value ->
                    repeat(3) { emit("$value - $it") }
                }.toList()
        )
    }

    @Test
    fun reusePreviousCollector() = testScope.runTest {
        params().forEach { reusePreviousCollector(it) }
    }

    private suspend fun reusePreviousCollector(impl: Impl) {
        var prevCollector: FlowCollector<String>? = null
        assertContentEquals(
            listOf("x-2", "x-3"),
            flowOf(1, 2, 3)
                .onEach { delay(1) }
                .testTransformLatest<Int, String>(impl) { value ->
                    if (prevCollector == null) {
                        prevCollector = this
                        awaitCancellation()
                    } else {
                        prevCollector?.emit("x-$value")
                    }
                }.toList()
        )
    }

    private fun <T, R> Flow<T>.testTransformLatest(
        impl: Impl,
        transform: suspend FlowCollector<R>.(value: T) -> Unit
    ): Flow<R> {
        return when (impl) {
            Impl.TRANSFORM_LATEST ->
                this@testTransformLatest.transformLatest(transform)
            Impl.SIMPLE_TRANSFORM_LATEST ->
                this@testTransformLatest.simpleTransformLatest(transform)
        }
    }

    companion object {
        fun params() = Impl.values()
    }

    enum class Impl {
        TRANSFORM_LATEST,
        SIMPLE_TRANSFORM_LATEST
    }
}