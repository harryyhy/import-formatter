package com.harryye.importplugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class ApplyAssetConfigAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AssetConfigManager.applyAssetSettings(project)
    }
}
