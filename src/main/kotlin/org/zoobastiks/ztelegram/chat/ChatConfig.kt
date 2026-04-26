package org.zoobastiks.ztelegram.chat

data class ChatConfig(
    val name: String,
    val isDefault: Boolean = false,
    val chatId: Long,
    val topicId: Int = 0,
    val minecraftFormat: String = "<%username%> %message%",
    val telegramFormat: String = "<b>%username%</b>: %message%",
    val prefix: String = "",
    val enabled: Boolean = true
)
