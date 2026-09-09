package io.github.darleywey.honoendpoints.endpoints

import com.intellij.lang.typescript.compiler.TypeScriptServiceHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Best-effort quickinfo requests for native documentation, never synchronous navigation resolution. */
internal object HonoTsServiceWarmup {
    private val LOG = Logger.getInstance(HonoTsServiceWarmup::class.java)
    private const val MAX_ATTEMPTS = 8
    private const val RETRY_COOLDOWN_MS = 2_000L
    private const val COMMAND_TIMEOUT_SECONDS = 20L
    private val STATE_KEY = Key.create<State>("hono.documentation.warmup")

    private data class RouteKey(val url: String, val offset: Int)

    // Keep caches on the project instead of retaining closed projects in a static map.
    private class State {
        val succeeded = ConcurrentHashMap.newKeySet<RouteKey>()
        val unsupported = ConcurrentHashMap.newKeySet<RouteKey>()
        val announcedFiles = ConcurrentHashMap.newKeySet<String>()
        val attempts = ConcurrentHashMap<RouteKey, AtomicInteger>()
        val lastAttempt = ConcurrentHashMap<RouteKey, Long>()

        fun shouldWarm(key: RouteKey): Boolean =
            key !in succeeded && key !in unsupported &&
                (attempts[key]?.get() ?: 0) < MAX_ATTEMPTS &&
                System.currentTimeMillis() - (lastAttempt[key] ?: 0L) >= RETRY_COOLDOWN_MS
    }

    private fun state(project: Project): State = synchronized(STATE_KEY) {
        project.getUserData(STATE_KEY) ?: State().also { project.putUserData(STATE_KEY, it) }
    }

    /** Queue work only; callers include the EDT and native documentation read actions. */
    fun warmRouteElements(project: Project, elements: List<PsiElement>) {
        if (project.isDisposed || elements.isEmpty() || ApplicationManager.getApplication().isUnitTestMode) return
        val state = state(project)
        HonoWarmupReadAction.submit(project, read = {
            elements.mapNotNull { element ->
                if (!element.isValid) return@mapNotNull null
                val file = element.containingFile?.virtualFile ?: return@mapNotNull null
                val key = RouteKey(file.url, element.textOffset)
                if (state.shouldWarm(key)) key to element else null
            }
        }, consume = { dispatch ->
            for ((key, element) in dispatch) {
                if (project.isDisposed) break
                // Requests from startup, discovery and selection may have queued the same route.
                if (!state.shouldWarm(key)) continue
                state.lastAttempt[key] = System.currentTimeMillis()
                warm(project, element, key, state)
            }
        })
    }

    private fun warm(project: Project, element: PsiElement, key: RouteKey, state: State) {
        val app = ApplicationManager.getApplication()
        app.assertIsNonDispatchThread()
        check(!app.isReadAccessAllowed) { "Quickinfo waits must not hold a read lock" }
        var warmedFile: VirtualFile? = null
        var future: Future<*>? = null
        try {
            // Only request construction touches PSI. This read action supplies a cancellation
            // context and yields to writes; waiting for tsserver happens after the lock is released.
            future = ReadAction.nonBlocking<Future<*>?> {
                if (!element.isValid) return@nonBlocking null
                val file = element.containingFile?.virtualFile ?: return@nonBlocking null
                warmedFile = file
                val holder = TypeScriptServiceHolder.getForElement(element, true) ?: return@nonBlocking null
                holder.service.getQuickInfoAt(element, holder.file)
            }
                .inSmartMode(project)
                .expireWith(project)
                .executeSynchronously()
            val file = warmedFile ?: return
            if (future == null) {
                state.unsupported.add(key)
                if (state.announcedFiles.add(key.url)) {
                    LOG.info("Hono warmup: quickinfo unavailable for $file; skipping")
                }
            } else {
                val info = future.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (project.isDisposed) return
                if (info == null) {
                    recordFailure(state, key, file, "quickinfo returned no result")
                } else {
                    state.succeeded.add(key)
                    if (state.announcedFiles.add(key.url)) {
                        LOG.info("Hono warmup: quickinfo received for $file")
                    }
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ProcessCanceledException(e)
        } catch (e: TimeoutException) {
            future?.cancel(false)
            recordFailure(state, key, warmedFile, "quickinfo timed out")
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is ProcessCanceledException -> throw cause
                is CancellationException -> throw cause
                else -> recordFailure(state, key, warmedFile, "quickinfo failed: ${cause?.message ?: e.message}")
            }
        } catch (e: Exception) {
            recordFailure(state, key, warmedFile, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun recordFailure(state: State, key: RouteKey, file: VirtualFile?, reason: String) {
        val attempt = state.attempts.getOrPut(key) { AtomicInteger() }.incrementAndGet()
        if (attempt <= 2 || attempt == MAX_ATTEMPTS) {
            LOG.warn("Hono warmup: attempt $attempt/$MAX_ATTEMPTS failed for $file: $reason")
        }
    }
}
