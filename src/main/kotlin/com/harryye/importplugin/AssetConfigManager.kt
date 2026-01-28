package com.harryye.importplugin

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.JavaCodeStyleSettings
import com.intellij.psi.codeStyle.PackageEntry
import com.intellij.psi.codeStyle.PackageEntryTable

import com.intellij.openapi.diagnostic.Logger

object AssetConfigManager {
    private val LOG = Logger.getInstance(AssetConfigManager::class.java)

    fun isConfigCompliant(project: Project): Boolean {
        val nonCompliant = getNonCompliantRules(project)
        val result = nonCompliant.isEmpty()
        LOG.warn("isConfigCompliant Final Result: $result")
        return result
    }

    fun getNonCompliantRules(project: Project): List<String> {
        LOG.warn("Starting compliance check for project: ${project.name}")
        val nonCompliant = mutableListOf<String>()

        // 1. 检查应用级配置 (CodeInsightSettings)
        val codeInsightSettings = CodeInsightSettings.getInstance()
        val isAutoImportOn = isAutoImportEnabled(codeInsightSettings)
        LOG.warn("Check 1 - Auto Import (App Level): $isAutoImportOn")
        if (!isAutoImportOn) {
            nonCompliant.add("Auto Import (App Level) 未启用")
        }

        // 检查项目级 Auto Import 覆盖
        var isProjectAutoImportOn = true
        try {
            val workspaceSettings = com.intellij.codeInsight.CodeInsightWorkspaceSettings.getInstance(project)
            if (!workspaceSettings.isOptimizeImportsOnTheFly) {
                isProjectAutoImportOn = false
            }
        } catch (ignored: Exception) {
            // 类不存在或无法访问，忽略
        }
        LOG.warn("Check 1b - Auto Import (Project Level): $isProjectAutoImportOn")
        if (!isProjectAutoImportOn) {
            nonCompliant.add("Optimize Imports on the fly (Project Level) 未启用")
        }

        val codeStyleSettings = CodeStyleSettingsManager.getInstance(project).currentSettings
        val javaSettings = codeStyleSettings.getCustomSettings(JavaCodeStyleSettings::class.java)

        // 1c. 检查 Indent detection 是否关闭
        val isIndentDetectionDisabled = isIndentDetectionDisabled(codeStyleSettings)
        LOG.warn("Check 1c - Indent Detection Disabled: $isIndentDetectionDisabled")
        if (!isIndentDetectionDisabled) {
            nonCompliant.add("Indent detection 未关闭")
        }

        // 2. 检查通配符导入设置 (Wildcard Import)
        val isNoWildcard = javaSettings.CLASS_COUNT_TO_USE_IMPORT_ON_DEMAND >= 99 &&
                           javaSettings.NAMES_COUNT_TO_USE_IMPORT_ON_DEMAND >= 99
        LOG.warn("Check 2 - No Wildcard: $isNoWildcard (ClassCount: ${javaSettings.CLASS_COUNT_TO_USE_IMPORT_ON_DEMAND}, NamesCount: ${javaSettings.NAMES_COUNT_TO_USE_IMPORT_ON_DEMAND})")
        if (!isNoWildcard) {
            nonCompliant.add("禁止使用通配符导入 (Class/Names Count >= 99)")
        }

        // 3. 检查 Import Layout (简单检查是否包含 java.* 和 javax.* 在最前面)
        val layoutTable = javaSettings.IMPORT_LAYOUT_TABLE
        val isLayoutCorrect = isImportLayoutCorrect(layoutTable)
        LOG.warn("Check 3 - Import Layout: $isLayoutCorrect")
        if (!isLayoutCorrect) {
            nonCompliant.add("Import Layout 顺序不符合 (java.*, javax.*, other, blank, static)")
        }

        // 3b. 检查 Module imports 是否排在最前
        val isModuleImportsFirst = isModuleImportsFirst(layoutTable)
        LOG.warn("Check 3b - Module Imports First: $isModuleImportsFirst")
        if (!isModuleImportsFirst) {
            nonCompliant.add("Module imports 未排在最前")
        }

        // 4. 检查其他 General 配置
        val isGeneralSettingsCorrect = javaSettings.isUseSingleClassImports &&
                                       javaSettings.INSERT_INNER_CLASS_IMPORTS &&
                                       javaSettings.LAYOUT_STATIC_IMPORTS_SEPARATELY
        LOG.warn("Check 4 - General Settings: $isGeneralSettingsCorrect (Single: ${javaSettings.isUseSingleClassImports}, Inner: ${javaSettings.INSERT_INNER_CLASS_IMPORTS}, StaticSep: ${javaSettings.LAYOUT_STATIC_IMPORTS_SEPARATELY})")
        if (!javaSettings.isUseSingleClassImports) {
            nonCompliant.add("Use single class imports 未开启")
        }
        if (!javaSettings.INSERT_INNER_CLASS_IMPORTS) {
            nonCompliant.add("Insert inner class imports 未开启")
        }
        if (!javaSettings.LAYOUT_STATIC_IMPORTS_SEPARATELY) {
            nonCompliant.add("Layout static imports separately 未开启")
        }
                                       
        // 5. 检查 Packages to Use Import with '*' 是否为空
        val isPackagesToUseImportOnDemandEmpty = javaSettings.PACKAGES_TO_USE_IMPORT_ON_DEMAND.entryCount == 0
        LOG.warn("Check 5 - Packages to Use Import on Demand Empty: $isPackagesToUseImportOnDemandEmpty (Count: ${javaSettings.PACKAGES_TO_USE_IMPORT_ON_DEMAND.entryCount})")
        if (!isPackagesToUseImportOnDemandEmpty) {
            nonCompliant.add("Packages to Use Import with '*' 不为空")
        }

        // 6. 检查 USE_FQ_CLASS_NAMES 是否为 false
        var isUseFqClassNamesCorrect = true
        try {
            val field = javaSettings.javaClass.getField("USE_FQ_CLASS_NAMES")
            if (field.getBoolean(javaSettings)) {
                isUseFqClassNamesCorrect = false
            }
        } catch (ignored: Exception) {
            try {
                val field = javaSettings.javaClass.getField("USE_FULLY_QUALIFIED_CLASS_NAMES")
                if (field.getBoolean(javaSettings)) {
                    isUseFqClassNamesCorrect = false
                }
            } catch (ignored2: Exception) {}
        }
        LOG.warn("Check 6 - Use FQ Class Names: $isUseFqClassNamesCorrect")
        if (!isUseFqClassNamesCorrect) {
            nonCompliant.add("Use fully qualified class names 未关闭")
        }

        if (nonCompliant.isEmpty()) {
            LOG.warn("Compliance check: All rules are compliant")
        } else {
            LOG.warn("Non-compliant rules: ${nonCompliant.joinToString("; ")}")
        }

        return nonCompliant
    }

    fun applyAssetSettings(project: Project, silent: Boolean = false) {
        // 1. 修改 App Level Settings (CodeInsight)
        // CodeInsightSettings 是应用级单例
        // 注意：根据 UI 提示 "Settings marked with this icon are only applied to the current project"
        // 某些设置可能需要通过 Project 级别的 CodeInsightWorkspaceSettings 来设置
        ApplicationManager.getApplication().invokeLater {
            try {
                // 设置应用级配置
                val codeInsightSettings = CodeInsightSettings.getInstance()
                setAutoImportEnabled(codeInsightSettings)
                
                // 尝试设置项目级配置 (如果存在)
                // CodeInsightWorkspaceSettings 负责管理项目特定的 CodeInsight 设置
                // 比如 Optimize imports on the fly 在某些版本可能是项目级的
                try {
                    val workspaceSettings = com.intellij.codeInsight.CodeInsightWorkspaceSettings.getInstance(project)
                    workspaceSettings.isOptimizeImportsOnTheFly = true
                } catch (ignored: Exception) {
                    // 旧版本可能没有这个类或字段
                }
            } catch (e: Exception) {
                // LOG.error("Failed to set CodeInsightSettings", e)
            }
        }

        // 2. 修改 Project Level Settings (CodeStyle)
        // 需要在 WriteCommandAction 中执行
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project) {
                val manager = CodeStyleSettingsManager.getInstance(project)
                enablePerProjectSettings(manager)
                val currentSettings = manager.currentSettings
                val mainSettings = getMainProjectCodeStyleSettings(manager)

                val settingsToUpdate = LinkedHashSet<com.intellij.psi.codeStyle.CodeStyleSettings>()
                settingsToUpdate.add(currentSettings)
                if (mainSettings != null) settingsToUpdate.add(mainSettings)

                settingsToUpdate.forEach { settings ->
                    val javaSettings = settings.getCustomSettings(JavaCodeStyleSettings::class.java)

                    // 关闭 Indent detection（Detect and use existing file indents）
                    setBooleanFieldIfPossible(settings, "DETECT_INDENTS", false)
                    setBooleanFieldIfPossible(settings, "ENABLE_INDENT_DETECTION", false)
                    setBooleanFieldIfPossible(settings, "INDENT_DETECTION", false)

                    javaSettings.CLASS_COUNT_TO_USE_IMPORT_ON_DEMAND = 99
                    javaSettings.NAMES_COUNT_TO_USE_IMPORT_ON_DEMAND = 99
                    
                    // 清空 Packages to Use Import with '*'
                    javaSettings.PACKAGES_TO_USE_IMPORT_ON_DEMAND = PackageEntryTable()
                    
                    val layoutTable = PackageEntryTable()
                    val moduleEntry = resolveModuleImportEntry(javaSettings.IMPORT_LAYOUT_TABLE)
                    if (moduleEntry != null) {
                        layoutTable.addEntry(moduleEntry)
                    } else {
                        LOG.warn("Module imports entry not found; will skip module imports rule")
                    }
                    layoutTable.addEntry(PackageEntry(false, "java", true))
                    layoutTable.addEntry(PackageEntry(false, "javax", true))
                    layoutTable.addEntry(PackageEntry.ALL_OTHER_IMPORTS_ENTRY)
                    layoutTable.addEntry(PackageEntry.BLANK_LINE_ENTRY)
                    layoutTable.addEntry(PackageEntry.ALL_OTHER_STATIC_IMPORTS_ENTRY)
                    
                    javaSettings.IMPORT_LAYOUT_TABLE = layoutTable

                    javaSettings.isUseSingleClassImports = true
                    javaSettings.INSERT_INNER_CLASS_IMPORTS = true
                    javaSettings.LAYOUT_STATIC_IMPORTS_SEPARATELY = true
                    
                    // 兼容性处理：使用反射设置新版本才有的字段
                    try {
                        LOG.warn("Attempting to set USE_FULLY_QUALIFIED_CLASS_NAMES to false")
                        // 根据日志，字段名实际上是 USE_FQ_CLASS_NAMES
                        val field = javaSettings.javaClass.getField("USE_FQ_CLASS_NAMES")
                        LOG.warn("Found USE_FQ_CLASS_NAMES field, current value: ${field.getBoolean(javaSettings)}")
                        field.setBoolean(javaSettings, false)
                        LOG.warn("Successfully set USE_FQ_CLASS_NAMES to false")
                    } catch (e: Exception) {
                        LOG.warn("Failed to set USE_FQ_CLASS_NAMES: ${e.message}")
                        try {
                            // 备选方案：尝试旧名称
                            val field = javaSettings.javaClass.getField("USE_FULLY_QUALIFIED_CLASS_NAMES")
                            field.setBoolean(javaSettings, false)
                        } catch (ignored: Exception) {}
                    }

                    setFieldIfPossible(javaSettings, "isDoNotSeparateModuleImports", true)
                    setFieldIfPossible(javaSettings, "isDeleteUnusedModuleImports", false)
                    
                    // FULLY_QUALIFIED_NAMES_IF_NOT_IMPORTED 是常量值 2
                    setFieldIfPossible(javaSettings, "CLASS_NAMES_IN_JAVADOC", 2)
                }

                // 尝试将修改写回项目级主配置，避免重启后被重置
                if (mainSettings != null) {
                    try {
                        val method = manager.javaClass.getMethod(
                            "setMainProjectCodeStyle",
                            com.intellij.psi.codeStyle.CodeStyleSettings::class.java
                        )
                        method.invoke(manager, mainSettings)
                    } catch (ignored: Exception) {}
                }

                notifyCodeStyleSettingsChanged(manager)

                if (!silent) {
                    Notifications.Bus.notify(
                        Notification(
                            "Asset Config Checker",
                            "配置已更新",
                            "已成功应用 Asset 编码规范配置 (含 Import 顺序及详细规则)。",
                            NotificationType.INFORMATION
                        ),
                        project
                    )
                }
            }
        }
    }

    private fun isImportLayoutCorrect(table: PackageEntryTable?): Boolean {
        if (table == null || table.entryCount < 2) {
            LOG.warn("Layout check failed: table is null or too short")
            return false
        }
        LOG.warn("Layout table entries: ${dumpImportLayout(table)}")
        val offset = if (table.entryCount >= 3 && isModuleImportEntry(table.getEntryAt(0))) 1 else 0
        if (table.entryCount < offset + 2) {
            LOG.warn("Layout check failed: table too short for java/javax after module entry")
            return false
        }
        val entry1 = table.getEntryAt(0 + offset)
        val entry2 = table.getEntryAt(1 + offset)
        
        val isJavaFirst = entry1.packageName == "java" && !entry1.isStatic && entry1.isWithSubpackages
        val isJavaxSecond = entry2.packageName == "javax" && !entry2.isStatic && entry2.isWithSubpackages
        
        if (!isJavaFirst) LOG.warn("Layout check failed: First entry is not java.* (Actual: ${entry1.packageName}, Static: ${entry1.isStatic})")
        if (!isJavaxSecond) LOG.warn("Layout check failed: Second entry is not javax.* (Actual: ${entry2.packageName}, Static: ${entry2.isStatic})")
        
        return isJavaFirst && isJavaxSecond
    }

    private fun isAutoImportEnabled(settings: CodeInsightSettings): Boolean {
        return try {
            // 根据日志，字段名拼写有误：ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY
            val addImportsField = try {
                settings.javaClass.getField("ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY")
            } catch (e: Exception) {
                settings.javaClass.getField("ADD_UNAMBIGUOUS_IMPORTS_ON_THE_FLY")
            }
            val optimizeImportsField = settings.javaClass.getField("OPTIMIZE_IMPORTS_ON_THE_FLY")
            
            val isAddUnambiguous = addImportsField.getBoolean(settings)
            val isOptimize = optimizeImportsField.getBoolean(settings)
            
            LOG.warn("AutoImport Check - AddUnambiguous: $isAddUnambiguous, Optimize: $isOptimize, OnPaste: ${settings.ADD_IMPORTS_ON_PASTE}")
            
            // 两个都必须是 TRUE
            isAddUnambiguous && isOptimize && settings.ADD_IMPORTS_ON_PASTE == CodeInsightSettings.YES
        } catch (e: Exception) {
            LOG.warn("AutoImport Check Failed with Exception: ${e.message}")
            try {
                 val addImportsField = settings.javaClass.getField("ADD_IMPORTS_ON_THE_FLY")
                 val optimizeImportsField = settings.javaClass.getField("OPTIMIZE_IMPORTS_ON_THE_FLY")
                 
                 val isAddUnambiguous = addImportsField.getBoolean(settings)
                 val isOptimize = optimizeImportsField.getBoolean(settings)
                 
                 LOG.warn("AutoImport Check (Fallback) - AddUnambiguous: $isAddUnambiguous, Optimize: $isOptimize, OnPaste: ${settings.ADD_IMPORTS_ON_PASTE}")

                 isAddUnambiguous && isOptimize && settings.ADD_IMPORTS_ON_PASTE == CodeInsightSettings.YES
            } catch (ignored: Exception) {
                true
            }
        }
    }

    private fun setAutoImportEnabled(settings: CodeInsightSettings) {
        LOG.warn("Starting setAutoImportEnabled...")
        
        // Debug: Print all fields
        try {
            val fields = settings.javaClass.fields
            LOG.warn("Available fields in CodeInsightSettings: ${fields.joinToString { it.name }}")
        } catch (e: Exception) {
            LOG.error("Failed to list fields", e)
        }

        settings.ADD_IMPORTS_ON_PASTE = CodeInsightSettings.YES
        setFieldIfPossible(settings, "SHOW_IMPORT_POPUP", true)
        
        // 尝试使用 setter 方法，因为有些版本可能是属性
        try {
            // 注意：根据日志，字段名是 ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY (拼写错误: UNAMBIGIOUS)
            val addImportsField = settings.javaClass.getField("ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY")
            LOG.warn("Found ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY field, setting to true")
            addImportsField.setBoolean(settings, true)
        } catch (e: Exception) {
            LOG.warn("Failed to set ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY: ${e.message}")
            try {
                // 尝试正确的拼写
                val addImportsField = settings.javaClass.getField("ADD_UNAMBIGUOUS_IMPORTS_ON_THE_FLY")
                LOG.warn("Found ADD_UNAMBIGUOUS_IMPORTS_ON_THE_FLY field, setting to true")
                addImportsField.setBoolean(settings, true)
            } catch (e2: Exception) {
                LOG.warn("Failed to set ADD_UNAMBIGUOUS_IMPORTS_ON_THE_FLY: ${e2.message}")
                try {
                    val addImportsField = settings.javaClass.getField("ADD_IMPORTS_ON_THE_FLY")
                    LOG.warn("Found ADD_IMPORTS_ON_THE_FLY field, setting to true")
                    addImportsField.setBoolean(settings, true)
                } catch (ignored: Exception) {
                    LOG.warn("Failed to set ADD_IMPORTS_ON_THE_FLY: ${ignored.message}")
                }
            }
        }
        
        try {
            val optimizeImportsField = settings.javaClass.getField("OPTIMIZE_IMPORTS_ON_THE_FLY")
            LOG.warn("Found OPTIMIZE_IMPORTS_ON_THE_FLY field, setting to true")
            optimizeImportsField.setBoolean(settings, true)
            // 打印更改为 true 后的值
            try {
                val value = optimizeImportsField.getBoolean(settings)
                LOG.warn("OPTIMIZE_IMPORTS_ON_THE_FLY after set: $value")
            } catch (e: Exception) {
                LOG.warn("Failed to read OPTIMIZE_IMPORTS_ON_THE_FLY after set: ${e.message}")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to set OPTIMIZE_IMPORTS_ON_THE_FLY: ${e.message}")
        }
        LOG.warn("Finished setAutoImportEnabled")
    }

    private fun setFieldIfPossible(obj: Any, fieldName: String, value: Any) {
        try {
            val field = obj.javaClass.getField(fieldName)
            field.set(obj, value)
        } catch (e: Exception) {
            // 字段不存在（可能是旧版本 SDK），忽略
        }
    }

    private fun setBooleanFieldIfPossible(obj: Any, fieldName: String, value: Boolean) {
        try {
            val field = obj.javaClass.getField(fieldName)
            field.setBoolean(obj, value)
        } catch (e: Exception) {
            // 字段不存在（可能是旧版本 SDK），忽略
        }
    }

    private fun isIndentDetectionDisabled(settings: com.intellij.psi.codeStyle.CodeStyleSettings): Boolean {
        val fields = listOf("DETECT_INDENTS", "ENABLE_INDENT_DETECTION", "INDENT_DETECTION")
        for (name in fields) {
            try {
                val field = settings.javaClass.getField(name)
                val enabled = field.getBoolean(settings)
                LOG.warn("Indent detection field $name = $enabled")
                return !enabled
            } catch (ignored: Exception) {
                // try next
            }
        }
        // 如果字段不存在，默认认为不影响合规
        return true
    }

    private fun getMainProjectCodeStyleSettings(
        manager: CodeStyleSettingsManager
    ): com.intellij.psi.codeStyle.CodeStyleSettings? {
        return try {
            val method = manager.javaClass.getMethod("getMainProjectCodeStyle")
            method.invoke(manager) as? com.intellij.psi.codeStyle.CodeStyleSettings
        } catch (e: Exception) {
            null
        }
    }

    private fun notifyCodeStyleSettingsChanged(manager: CodeStyleSettingsManager) {
        try {
            val method = manager.javaClass.getMethod("notifyCodeStyleSettingsChanged")
            method.invoke(manager)
        } catch (e: Exception) {
            // 旧版本可能没有该方法，忽略
        }
    }

    private fun enablePerProjectSettings(manager: CodeStyleSettingsManager) {
        try {
            val method = manager.javaClass.getMethod("setUsePerProjectSettings", Boolean::class.javaPrimitiveType)
            method.invoke(manager, true)
        } catch (e: Exception) {
            setFieldIfPossible(manager, "USE_PER_PROJECT_SETTINGS", true)
        }
    }

    @Volatile
    private var packageEntryIntrospectionLogged = false

    private fun getModuleImportsEntryOrNull(): PackageEntry? {
        // 兼容不同版本字段名：MODULE_IMPORTS_ENTRY / ALL_MODULE_IMPORTS_ENTRY 等
        return try {
            val fields = PackageEntry::class.java.fields
            val field = fields.firstOrNull { f ->
                f.type == PackageEntry::class.java &&
                    f.name.uppercase().contains("MODULE") &&
                    f.name.uppercase().contains("IMPORT")
            } ?: return null
            field.get(null) as? PackageEntry
        } catch (e: Exception) {
            null
        }
    }

    private fun findModuleImportEntryInTable(table: PackageEntryTable?): PackageEntry? {
        if (table == null) return null
        for (i in 0 until table.entryCount) {
            val entry = table.getEntryAt(i)
            if (isModuleImportEntry(entry)) {
                LOG.warn("Found module import entry in existing table at index $i: ${describeEntry(entry)}")
                return entry
            }
        }
        return null
    }

    private fun resolveModuleImportEntry(existingTable: PackageEntryTable?): PackageEntry? {
        findModuleImportEntryInTable(existingTable)?.let { return it }
        val staticEntry = getModuleImportsEntryOrNull()
        if (staticEntry != null) {
            LOG.warn("Using module import entry from PackageEntry static field: ${describeEntry(staticEntry)}")
            return staticEntry
        }
        logPackageEntryIntrospectionOnce()
        return null
    }

    private fun isModuleImportEntry(entry: PackageEntry?): Boolean {
        if (entry == null) return false
        return try {
            val method = entry.javaClass.methods.firstOrNull { it.name == "isModuleImport" && it.parameterCount == 0 }
            if (method != null) {
                method.invoke(entry) as? Boolean ?: false
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isModuleImportsFirst(table: PackageEntryTable?): Boolean {
        val moduleEntryInTable = findModuleImportEntryInTable(table)
        if (moduleEntryInTable == null) {
            val staticEntry = getModuleImportsEntryOrNull()
            if (staticEntry == null) {
                LOG.warn("Module imports entry not found; treat as not supported")
                return true
            }
            LOG.warn("Module imports entry supported but not present in layout")
            return false
        }
        if (table == null || table.entryCount == 0) return false
        val first = table.getEntryAt(0)
        val isFirst = isModuleImportEntry(first)
        LOG.warn("Module imports first check: isFirst=$isFirst, firstEntry=${describeEntry(first)}, moduleEntry=${describeEntry(moduleEntryInTable)}")
        return isFirst
    }

    private fun logPackageEntryIntrospectionOnce() {
        if (packageEntryIntrospectionLogged) return
        packageEntryIntrospectionLogged = true
        try {
            val fieldNames = PackageEntry::class.java.fields.joinToString { it.name }
            LOG.warn("PackageEntry fields: $fieldNames")
            val ctorSignatures = PackageEntry::class.java.declaredConstructors.joinToString(" | ") { ctor ->
                ctor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }
            }
            LOG.warn("PackageEntry constructors: $ctorSignatures")
        } catch (e: Exception) {
            LOG.warn("Failed to introspect PackageEntry: ${e.message}")
        }
    }

    private fun dumpImportLayout(table: PackageEntryTable): String {
        val entries = mutableListOf<String>()
        for (i in 0 until table.entryCount) {
            entries.add(describeEntry(table.getEntryAt(i)))
        }
        return entries.joinToString(" | ")
    }

    private fun describeEntry(entry: PackageEntry?): String {
        if (entry == null) return "<null>"
        return try {
            val isModule = isModuleImportEntry(entry)
            "pkg=${entry.packageName}, static=${entry.isStatic}, withSub=${entry.isWithSubpackages}, module=$isModule"
        } catch (e: Exception) {
            "<error:${e.message}>"
        }
    }
}
