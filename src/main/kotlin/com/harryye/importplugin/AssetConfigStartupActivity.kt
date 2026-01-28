package com.harryye.importplugin

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class AssetConfigStartupActivity : ProjectActivity {
    private val log = Logger.getInstance(AssetConfigStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        // 检查配置是否符合规范
        val nonCompliant = AssetConfigManager.getNonCompliantRules(project)
        if (nonCompliant.isNotEmpty()) {
            log.warn("Startup non-compliant rules: ${nonCompliant.joinToString("; ")}")
            showNotification(project, nonCompliant)
        }
    }

    private fun showNotification(project: Project, nonCompliant: List<String>) {
        val rulesText = nonCompliant.joinToString("<br>• ", prefix = "不符合规则：<br>• ")
        val notification = Notification(
            "Asset Config Checker",
            "检测到非标准配置",
            "当前项目配置不符合 Asset 编码规范。<br>$rulesText<br><br>是否自动修复？",
            NotificationType.WARNING
        )

        notification.addAction(object : AnAction("立即修复 (Fix Now)") {
            override fun actionPerformed(e: AnActionEvent) {
                AssetConfigManager.applyAssetSettings(project)
                notification.expire()
            }
        })

        Notifications.Bus.notify(notification, project)
    }
}
