package com.harryye.importplugin

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiDocumentManager

class FixFileImportsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)

        // 1. 确保配置是符合规范的
        // 我们不希望每次都弹窗，所以这里调用一个静默的应用方法
        AssetConfigManager.applyAssetSettings(project, silent = true)

        // 2. 对当前文件执行 Optimize Imports
        // 需要先保存文档，确保 PSI 与 Document 同步
        val documentManager = PsiDocumentManager.getInstance(project)
        if (editor != null) {
            documentManager.commitDocument(editor.document)
        }

        // 运行优化导入处理器
        OptimizeImportsProcessor(project, file).run()
    }
    
    override fun update(e: AnActionEvent) {
        // 只有在有项目和文件时才启用
        val project = e.project
        val file = e.getData(CommonDataKeys.PSI_FILE)
        
        // 调试日志：确认是否进入 update 方法
        // com.intellij.openapi.diagnostic.Logger.getInstance(FixFileImportsAction::class.java).warn("FixFileImportsAction update: project=$project, file=$file")

        // 显式设置可见性
        e.presentation.isEnabledAndVisible = project != null && file != null
    }
}
