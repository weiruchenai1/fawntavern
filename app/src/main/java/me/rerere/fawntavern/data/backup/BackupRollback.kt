package me.rerere.fawntavern.data.backup

internal data class BackupRollbackStep(
    val description: String,
    val action: suspend () -> Unit,
)

/** 尽最大努力执行全部回滚步骤，并把每个失败保留在最初的导入异常上。 */
internal suspend fun rollbackAfterFailure(
    cause: Exception,
    steps: List<BackupRollbackStep>,
    onStepFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    steps.forEach { step ->
        try {
            step.action()
        } catch (rollbackError: Throwable) {
            if (rollbackError !== cause) cause.addSuppressed(rollbackError)
            runCatching { onStepFailure(step.description, rollbackError) }
        }
    }
}
