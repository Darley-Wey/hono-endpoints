package io.github.darleywey.honoendpoints.endpoints

import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.concurrency.CancellablePromise
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Exercises the production scheduler, unlike discovery tests that skip TypeScript service warmup. */
class HonoWarmupConcurrencyTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testReadYieldsToWriteAndRetries() {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val app = ApplicationManager.getApplication()
        val started = CompletableFuture<Unit>()
        val completed = CompletableFuture<Int>()
        val writeFinished = AtomicBoolean()
        val readTimedOut = AtomicBoolean()
        val attempts = AtomicInteger()
        val cancellations = AtomicInteger()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val task = HonoWarmupReadAction.submit(project, read = {
            assertTrue(app.isReadAccessAllowed)
            assertFalse(app.isDispatchThread)
            assertNotNull(ProgressManager.getInstance().progressIndicator)
            val attempt = attempts.incrementAndGet()
            started.complete(Unit)
            try {
                while (!writeFinished.get()) {
                    ProgressManager.checkCanceled()
                    if (System.nanoTime() >= deadline) {
                        readTimedOut.set(true)
                        break
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
                }
            } catch (e: ProcessCanceledException) {
                cancellations.incrementAndGet()
                throw e
            }
            attempt
        }, consume = { completed.complete(it) })
        try {
            started.get(10, TimeUnit.SECONDS)
            app.invokeAndWait {
                WriteAction.run<RuntimeException> { writeFinished.set(true) }
            }
            completed.get(10, TimeUnit.SECONDS)
            assertFalse("A writer must preempt the scan, not wait for its timeout", readTimedOut.get())
            assertTrue("The pending write must cancel the active read", cancellations.get() > 0)
            assertTrue("The cancelled scan must retry after the write", attempts.get() > 1)
        } finally {
            writeFinished.set(true)
            task.cancel()
        }
    }

    fun testServiceWaitIsOffEdtAndOutsideReadAction() {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val app = ApplicationManager.getApplication()
        val serviceReply = CompletableFuture<String>()
        val waiting = CompletableFuture<Unit>()
        val completed = CompletableFuture<String>()
        lateinit var task: CancellablePromise<CompletableFuture<String>>
        // Provider callbacks can originate on the EDT. Even an immediately completed
        // read must hand service waits to the bounded worker, never an inline callback.
        app.invokeAndWait {
            task = HonoWarmupReadAction.submit(project, read = { serviceReply }, consume = { reply ->
                try {
                    assertFalse("Service waits must not run on the EDT", app.isDispatchThread)
                    assertFalse("Service waits must not retain a read lock", app.isReadAccessAllowed)
                    waiting.complete(Unit)
                    completed.complete(reply.get(10, TimeUnit.SECONDS))
                } catch (t: Throwable) {
                    waiting.completeExceptionally(t)
                    completed.completeExceptionally(t)
                }
            })
        }
        try {
            waiting.get(10, TimeUnit.SECONDS)
            // The service only responds after a UI write. A read lock held during
            // Future.get would block this write until the fake service times out.
            app.invokeAndWait {
                WriteAction.run<RuntimeException> { serviceReply.complete("ready") }
            }
            assertEquals("ready", completed.get(10, TimeUnit.SECONDS))
        } finally {
            serviceReply.complete("cleanup")
            task.cancel()
        }
    }

    fun testCancellingScanDoesNotStartServiceWork() {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val started = CompletableFuture<Unit>()
        val stopped = CompletableFuture<Unit>()
        val consumed = AtomicBoolean()
        val cleanup = AtomicBoolean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val task = HonoWarmupReadAction.submit(project, read = {
            started.complete(Unit)
            try {
                while (!cleanup.get() && System.nanoTime() < deadline) {
                    ProgressManager.checkCanceled()
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
                }
            } finally {
                stopped.complete(Unit)
            }
        }, consume = { consumed.set(true) })
        try {
            started.get(10, TimeUnit.SECONDS)
            task.cancel()
            stopped.get(10, TimeUnit.SECONDS)
            assertTrue(task.isCancelled)
            assertFalse("A cancelled scan must not send service requests", consumed.get())
        } finally {
            cleanup.set(true)
            task.cancel()
        }
    }

    fun testStartupScanKeepsNativeMethodReference() {
        val app = ApplicationManager.getApplication()
        app.invokeAndWait {
            myFixture.addFileToProject("routes.ts", """
                import { Hono } from 'hono'
                new Hono().get('/startup', (c) => c.text('ok'))
            """.trimIndent())
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val task = HonoStartupWarmup().schedule(project)
        try {
            val elements = task.blockingGet(10, TimeUnit.SECONDS)!!
            ReadAction.run<RuntimeException> {
                assertSize(1, elements)
                assertTrue(elements.single() is JSReferenceExpression)
                assertEquals("get", (elements.single() as JSReferenceExpression).referenceName)
            }
        } finally {
            task.cancel()
        }
    }
}
