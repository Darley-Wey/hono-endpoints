 package io.github.darleywey.honoendpoints.endpoints

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise

/** PSI work yields to pending writes; service waits never run on the caller or under a read lock. */
internal object HonoWarmupReadAction {
    private val worker = AppExecutorUtil.createBoundedApplicationPoolExecutor("Hono documentation warmup", 1)

    fun <T> submit(project: Project, read: () -> T, consume: (T) -> Unit): CancellablePromise<T> {
        val promise = ReadAction.nonBlocking<T> { read() }
            .inSmartMode(project)
            .expireWith(project)
            .submit(AppExecutorUtil.getAppExecutorService())
        promise.onSuccess { result ->
            // A completed promise invokes callbacks inline on the registering thread,
            // which can be the EDT or a thread already holding a read lock.
            worker.execute {
                if (!project.isDisposed) consume(result)
            }
        }
        return promise
    }
}
