package org.zoobastiks.ztelegram.chat

import org.bukkit.Bukkit
import org.zoobastiks.ztelegram.ZTele

class ChatManager(private val plugin: ZTele) {
    private val chats = mutableListOf<ChatConfig>()
    private var defaultChat: ChatConfig? = null

    fun loadConfig() {
        chats.clear()
        if (!ZTele.conf.gameChatsEnabled) return

        val config = plugin.config
        val chatList = config.getMapList("game_chats.chats")
        for (chatMap in chatList) {
            val name = chatMap["name"]?.toString() ?: continue
            val isDefault = chatMap["isDefault"]?.toString()?.toBoolean() ?: false
            val chatId = chatMap["chatId"]?.toString()?.toLongOrNull() ?: continue
            val topicId = chatMap["topicId"]?.toString()?.toIntOrNull() ?: 0
            val minecraftFormat = chatMap["minecraftFormat"]?.toString()
                ?: "<gray>[G]</gray> <<sender>> <text>"
            val telegramFormat = chatMap["telegramFormat"]?.toString()
                ?: "<b>[<username>]</b> <text>"
            val prefix = chatMap["prefix"]?.toString() ?: ""
            val enabled = chatMap["enabled"]?.toString()?.toBoolean() ?: true

            val chat = ChatConfig(name, isDefault, chatId, topicId, minecraftFormat, telegramFormat, prefix, enabled)
            chats.add(chat)
            if (isDefault) defaultChat = chat
        }
        if (defaultChat == null && chats.isNotEmpty()) defaultChat = chats[0]
        plugin.logger.info("Loaded ${chats.size} game chats")
    }

    fun getChat(name: String?): ChatConfig? {
        if (name == null) return defaultChat
        return chats.find { it.name.equals(name, ignoreCase = true) }
    }

    fun getDefaultChat(): ChatConfig? = defaultChat

    fun getAllChats(): List<ChatConfig> = chats.toList()

    fun getChatByTelegramId(chatId: Long, topicId: Int): ChatConfig? {
        val exact = chats.find { it.chatId == chatId && it.topicId == topicId }
        if (exact != null) return exact
        val noTopic = chats.find { it.chatId == chatId && it.topicId == 0 }
        if (noTopic != null) return noTopic
        return defaultChat
    }

    fun sendToGame(chat: ChatConfig, username: String, message: String) {
        if (!ZTele.conf.gameChatsTelegramToMinecraft) return
        val component = ChatFormatter.formatMinecraftMessageFromTG(chat.minecraftFormat, username, message)
        Bukkit.getServer().sendMessage(component)
    }

    fun sendToTelegram(chat: ChatConfig, sender: String, message: String) {
        if (!ZTele.conf.gameChatsMinecraftToTelegram) return
        val formattedMessage = ChatFormatter.formatTelegramMessage(chat.telegramFormat, sender, message)
        val targetChatId = if (chat.topicId > 0) {
            "${chat.chatId}_${chat.topicId}"
        } else {
            chat.chatId.toString()
        }
        ZTele.bot.sendAutoDeleteMessage(targetChatId, formattedMessage, 0)
    }

    fun handleGameChatMessage(sender: String, message: String, telegramUsername: String?) {
        val defaultChat = defaultChat ?: return
        sendToTelegram(defaultChat, sender, message)
    }
}
