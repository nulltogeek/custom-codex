package dev.nulltogeek.codexremote

enum class NoticeKind { Connection, Search, Catalog, History, QueueStorage, QueueDelivery }
data class NoticeKey(val kind: NoticeKind, val chatId: String = "")

internal fun ScreenState.withInfoNotice(key: NoticeKey, message: String): ScreenState =
    copy(infoNotices = infoNotices + (key to message))

internal fun ScreenState.resolveInfoNotice(key: NoticeKey): ScreenState =
    copy(infoNotices = infoNotices - key)

internal fun ScreenState.withConnectionStatus(status: String): ScreenState {
    val next = copy(connection = status)
    return if (status == "Connected") next.resolveInfoNotice(NoticeKey(NoticeKind.Connection)) else next
}

internal fun ScreenState.resolveQueueNotices(queue: QueueSnapshot): ScreenState = copy(infoNotices = infoNotices.filterKeys { key ->
    key.kind != NoticeKind.QueueDelivery || queue.messages.any {
        it.chatId == key.chatId && (it.error.isNotEmpty() || it.delivery == "uncertain")
    }
})
