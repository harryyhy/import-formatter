package com.harryye.importplugin

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.JavaCodeStyleSettings

class AlibabaConfigStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // 检查配置是否符合规范
        if (!isConfigCompliant(project)) {
            showNotification(project)
        }
    }

    private fun isConfigCompliant(project: Project): Boolean {
        val codeInsightSettings = CodeInsightSettings.getInstance()
        val codeStyleSettings = CodeStyleSettingsManager.getSettings(project)
        val javaSettings = codeStyleSettings.getCustomSettings(JavaCodeStyleSettings::class.java)

        // 1. 检查自动导入设置 (Auto Import)
        // 使用反射以兼容不同版本的 IntelliJ SDK
        val isAutoImportOn = isAutoImportEnabled(codeInsightSettings)

        // 2. 检查通配符导入设置 (Wildcard Import) - 阿里巴巴规范禁止使用通配符导入
        // 通常设置为一个很大的数，比如 99 或 999
        val isNoWildcard = javaSettings.CLASS_COUNT_TO_USE_IMPORT_ON_DEMAND >= 99 &&
                           javaSettings.NAMES_COUNT_TO_USE_IMPORT_ON_DEMAND >= 99

        return isAutoImportOn && isNoWildcard
    }

    private fun isAutoImportEnabled(settings: CodeInsightSettings): Boolean {
        return try {
            // 尝试获取 ADD_UNAMBIGUOUS_IMPORTS_ON_THE_FLY 字段
            val addImportsField = settings.javaClass.getField("ADD_UNAMBIGUOUS_IMPORTS_ON_THE_FLY")
            val optimizeImportsField = settings.javaClass.getField("OPTIMIZE_IMPORTS_ON_THE_FLY")
            
            addImportsField.getBoolean(settings) && optimizeImportsField.getBoolean(settings)
        } catch (e: Exception) {
            // 如果字段不存在，尝试旧版本字段名或忽略
            try {
                 val addImportsField = settings.javaClass.getField("ADD_IMPORTS_ON_THE_FLY")
                 val optimizeImportsField = settings.javaClass.getField("OPTIMIZE_IMPORTS_ON_THE_FLY")
                 addImportsField.getBoolean(settings) && optimizeImportsField.getBoolean(settings)
            } catch (ignored: Exception) {
                // 如果都找不到，默认认为合规，避免误报
                true
            }
        }
    }

    private fun showNotification(project: Project) {
        val notification = Notification(
            "Alibaba Config Checker",
            "检测到非标准配置",
            "当前项目的 Auto Import 或 Code Style 配置不符合阿里巴巴编码规范（如未开启自动导包或允许通配符导入）。是否自动修复？",
            NotificationType.WARNING
        )

        notification.addAction(object : AnAction("立即修复 (Fix Now)") {
            override fun actionPerformed(e: AnActionEvent) {
                applyAlibabaSettings(project)
                notification.expire()
            }
        })

        Notifications.Bus.notify(notification, project)
    }

    private fun applyAlibabaSettings(project: Project) {
        // 修改配置需要写权限，虽然 CodeInsightSettings 是应用级别的，但 CodeStyle 是项目级别的
        // 为了安全起见，我们在 Write Action 中执行
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project) {
                // 1. 修复 Auto Import 设置
                val codeInsightSettings = CodeInsightSettings.getInstance()
                setAutoImportEnabled(codeInsightSettings)

                // 2. 修复 Wildcard Import 设置
                val manager = CodeStyleSettingsManager.getInstance(project)
                // 获取当前项目的临时配置（如果存在）或主配置
                val settings = manager.currentSettings
                val javaSettings = settings.getCustomSettings(JavaCodeStyleSettings::class.java)

                // 设置为 999，实际上禁用了 import java.util.* 这种写法
                javaSettings.CLASS_COUNT_TO_USE_IMPORT_ON_DEMAND = 999
                javaSettings.NAMES_COUNT_TO_USE_IMPORT_ON_DEMAND = 999
                
                // 强制不使用通配符导入
                // javaSettings.isUseSingleClassImports = true // 这个字段可能也不存在，暂时注释掉

                // 通知用户已更新
                Notifications.Bus.notify(
                    Notification(
                        "Alibaba Config Checker",
                        "配置已更新",
                        "已成功应用Asset编码规范配置。",
                        NotificationType.INFORMATION
                    ),
                    project
                )
            }
        }
    }

    private fun setAutoImportEnabled(settings: CodeInsightSettings) {
        try {
            val addImportsField = settings.javaClass.getField("ADD_UNAMBIGUOUS_IMPORTS_ON_THE_FLY")
            val optimizeImportsField = settings.javaClass.getField("OPTIMIZE_IMPORTS_ON_THE_FLY")
            
            addImportsField.setBoolean(settings, true)
            optimizeImportsField.setBoolean(settings, true)
        } catch (e: Exception) {
             try {
                 val addImportsField = settings.javaClass.getField("ADD_IMPORTS_ON_THE_FLY")
                 val optimizeImportsField = settings.javaClass.getField("OPTIMIZE_IMPORTS_ON_THE_FLY")
                 addImportsField.setBoolean(settings, true)
                 optimizeImportsField.setBoolean(settings, true)
            } catch (ignored: Exception) {
                // 忽略
            }
        }
    }
}
