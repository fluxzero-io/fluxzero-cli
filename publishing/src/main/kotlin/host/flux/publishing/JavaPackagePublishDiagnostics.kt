package host.flux.publishing

import java.time.Instant

fun interface JavaPackagePublishDiagnostics {
    fun record(event: JavaPackagePublishDiagnosticEvent)

    companion object {
        val NONE = JavaPackagePublishDiagnostics {}
    }
}

data class JavaPackagePublishDiagnosticEvent(
    val category: String,
    val message: String,
    val targetImage: String? = null,
    val targetReference: String? = null,
    val level: String? = null,
    val attempt: Int? = null,
    val timestamp: Instant = Instant.now(),
    val threadName: String = Thread.currentThread().name
) {
    fun toLogLine(): String = buildString {
        append(timestamp)
        append(" category=").append(category)
        level?.let { append(" level=").append(it) }
        targetImage?.let { append(" image=").append(it) }
        targetReference?.let { append(" reference=").append(it) }
        attempt?.let { append(" attempt=").append(it) }
        append(" thread=").append(threadName)
        append(" message=").append(message.oneLine())
    }

    private fun String.oneLine(): String =
        replace("\r", "\\r").replace("\n", "\\n")
}
