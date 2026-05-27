package org.zoobastiks.ztelegram.commands

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.zoobastiks.ztelegram.ZTele
import org.zoobastiks.ztelegram.chat.ChatConfig
import org.zoobastiks.ztelegram.utils.PlaceholderEngine

class TgSendCommand(private val plugin: ZTele) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("ztelegram.send")) {
            sender.sendMessage("§cНет прав на использование этой команды")
            return true
        }

        if (args.size < 4) {
            sender.sendMessage("§cИспользование: /tg <bc|bcs> <чат> <format> <сообщение>")
            sender.sendMessage("§7Форматы: plain, mm, html, json")
            sender.sendMessage("§7Чат: имя из game_chats или Telegram ID (например -1001234567890)")
            return true
        }

        val silent = args[0].equals("bcs", ignoreCase = true)
        val chatNameOrId = args[1]
        val format = args[2].lowercase()
        val rawMessage = args.drop(3).joinToString(" ")

        // Находим чат
        val chat = findChat(chatNameOrId)
        if (chat == null) {
            if (!silent) sender.sendMessage("§cЧат '$chatNameOrId' не найден")
            return true
        }

        val chatId = if (chat.topicId > 0) "${chat.chatId}_${chat.topicId}" else chat.chatId.toString()

        // Обработка формата
        val formattedMessage = when (format) {
            "plain" -> rawMessage
            "mm" -> {
                try {
                    val component = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(rawMessage)
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component)
                } catch (e: Exception) {
                    if (!silent) sender.sendMessage("§cОшибка парсинга MiniMessage: ${e.message}")
                    return true
                }
            }
            "html" -> rawMessage
            "json" -> {
                try {
                    val component = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(rawMessage)
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component)
                } catch (e: Exception) {
                    if (!silent) sender.sendMessage("§cОшибка парсинга JSON: ${e.message}")
                    return true
                }
            }
            else -> {
                if (!silent) sender.sendMessage("§cНеизвестный формат: $format. Используйте plain, mm, html, json")
                return true
            }
        }

        // Заменяем плейсхолдеры
        val finalMessage = if (sender is Player) {
            var msg = formattedMessage
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                msg = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(sender, msg)
            }
            val context = PlaceholderEngine.PlaceholderContext().apply {
                this.player = sender
            }
            PlaceholderEngine.process(msg, context)
        } else {
            var msg = formattedMessage
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                msg = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(null, msg)
            }
            PlaceholderEngine.process(msg, PlaceholderEngine.PlaceholderContext())
        }

        // Отправляем в Telegram (без автоудаления)
        try {
            ZTele.bot.sendAutoDeleteMessage(chatId, finalMessage, 0)
        } catch (e: Exception) {
            if (!silent) sender.sendMessage("§cОшибка отправки в Telegram: ${e.message}")
            return true
        }

        if (!silent) {
            sender.sendMessage("§aСообщение отправлено в чат '${chat.name}'")
        }
        return true
    }

    private fun findChat(nameOrId: String): ChatConfig? {
        // 1. Ищем по имени в game_chats
        val byName = ZTele.chatManager.getChat(nameOrId)
        if (byName != null) return byName

        // 2. Парсим как числовой ID или ID_топик
        val parts = nameOrId.split("_")
        val chatId = parts[0].toLongOrNull() ?: return null
        val topicId = parts.getOrNull(1)?.toIntOrNull() ?: 0
        // Возвращаем временный объект чата
        return ZTele.chatManager.getChatByTelegramId(chatId, topicId)
            ?: ChatConfig(
                name = nameOrId,
                chatId = chatId,
                topicId = topicId,
                enabled = true
            )
    }
}
