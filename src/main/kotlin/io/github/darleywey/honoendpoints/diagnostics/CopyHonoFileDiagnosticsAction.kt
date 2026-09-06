package io.github.darleywey.honoendpoints.diagnostics

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.darleywey.honoendpoints.framework.HonoSymbols
import java.awt.datatransfer.StringSelection

class CopyHonoFileDiagnosticsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null &&
            event.getData(CommonDataKeys.VIRTUAL_FILE)?.extension in HonoSymbols.SOURCE_EXTENSIONS
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        ReadAction.nonBlocking<String> { HonoFileDiagnostics.collect(project, file) }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { !file.isValid }
            .finishOnUiThread(ModalityState.defaultModalityState()) { report ->
                CopyPasteManager.getInstance().setContents(StringSelection(report))
                Messages.showInfoMessage(project, "File diagnostics copied to clipboard.", "Hono Endpoints")
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
