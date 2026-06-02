package org.zoobastiks.ztelegram.commands

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.zoobastiks.ztelegram.ZTele
import org.zoobastiks.ztelegram.chat.ChatConfig
import org.zoobastiks.ztelegram.utils.PlaceholderEngine
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage

/**
 * Команда для отправки сообщений из Minecraft в Telegram
 * 
 * Формат: /tgsend <bc|bcs> <чат> <формат> [reply_to=ID] <сообщение>
 */
class TgSendCommand(private val plugin: ZTele) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("ztelegram.send")) {
            sender.sendMessage("§cНет прав на использование этой команды")
            return true
        }

        if (args.size < 4) {
            sendHelp(sender)
            return true
        }

        val silent = args[0].equals("bcs", ignoreCase = true)
        val chatNameOrId = args[1]
        val format = args[2].lowercase()
        
        // Парсим аргументы: ищем reply_to=ID и собираем сообщение
        var replyToMessageId: Int? = null
        val messageParts = mutableListOf<String>()
        
        for (i in 3 until args.size) {
            val arg = args[i]
            if (arg.startsWith("reply_to=", ignoreCase = true)) {
                replyToMessageId = arg.substringAfter("=").toIntOrNull()
                if (replyToMessageId == null && !silent) {
                    sender.sendMessage("§cНеверный формат reply_to: ${arg.substringAfter("=")}")
                    return true
                }
            } else {
                messageParts.add(arg)
            }
        }
        
        val rawMessage = messageParts.joinToString(" ")

        // Находим чат или создаём временный по ID
        val chat = findChat(chatNameOrId)
        if (chat == null) {
            if (!silent) sender.sendMessage("§cЧат '$chatNameOrId' не найден")
            return true
        }

        // ⚠️ КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ:
        // Если в команде явно указан ID с топиком (содержит "_"), 
        // то используем ЕГО как есть, без преобразований!
        val chatId = if (chatNameOrId.contains("_")) {
            // Сохраняем исходный ID с топиком
            chatNameOrId
        } else if (chat.topicId > 0) {
            "${chat.chatId}_${chat.topicId}"
        } else {
            chat.chatId.toString()
        }

        if (ZTele.conf.debugEnabled) {
            plugin.logger.info("[TgSend] chatNameOrId='$chatNameOrId' -> chatId='$chatId'")
        }

        // ========== ОБРАБОТКА ПЕРЕНОСОВ СТРОК ==========
        var processedMessage = rawMessage
        
        processedMessage = processedMessage.replace("%0A", "\n", ignoreCase = true)
        processedMessage = processedMessage.replace("\\n", "\n")
        processedMessage = processedMessage.replace("<newLine>", "\n", ignoreCase = true)
        processedMessage = processedMessage.replace("<nl>", "\n", ignoreCase = true)
        processedMessage = processedMessage.replace("</newLine>", "\n", ignoreCase = true)
        processedMessage = processedMessage.replace("</nl>", "\n", ignoreCase = true)
        // ========== КОНЕЦ ОБРАБОТКИ ==========

        // Обработка формата и конвертация в HTML для Telegram
        val finalHtmlMessage = when (format) {
            "plain" -> escapeHtml(processedMessage)
            "mm" -> {
                try {
                    val component = MiniMessage.miniMessage().deserialize(processedMessage)
                    val plainText = PlainTextComponentSerializer.plainText().serialize(component)
                    escapeHtml(plainText)
                } catch (e: Exception) {
                    if (!silent) sender.sendMessage("§cОшибка парсинга MiniMessage: ${e.message}")
                    return true
                }
            }
            "markdown" -> markdownToTelegramHtml(processedMessage)
            "html" -> validateAndSanitizeHtml(processedMessage)
            "json" -> {
                try {
                    val component = GsonComponentSerializer.gson().deserialize(processedMessage)
                    val plainText = PlainTextComponentSerializer.plainText().serialize(component)
                    escapeHtml(plainText)
                } catch (e: Exception) {
                    if (!silent) sender.sendMessage("§cОшибка парсинга JSON: ${e.message}")
                    return true
                }
            }
            else -> {
                if (!silent) sender.sendMessage("§cНеизвестный формат: $format. Используйте plain, mm, html, markdown, json")
                return true
            }
        }

        // Заменяем плейсхолдеры
        val finalMessage = if (sender is Player) {
            var msg = finalHtmlMessage
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                msg = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(sender, msg)
            }
            val context = PlaceholderEngine.PlaceholderContext().apply {
                this.player = sender
            }
            PlaceholderEngine.process(msg, context)
        } else {
            var msg = finalHtmlMessage
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                msg = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(null, msg)
            }
            PlaceholderEngine.process(msg, PlaceholderEngine.PlaceholderContext())
        }

        // Отправляем в Telegram
        try {
            sendTelegramMessage(chatId, finalMessage, replyToMessageId)
            if (ZTele.conf.debugEnabled && !silent) {
                plugin.logger.info("📤 [TgSend] Отправлено в $chatId" + 
                    (if (replyToMessageId != null) " (reply to $replyToMessageId)" else ""))
            }
        } catch (e: Exception) {
            if (!silent) sender.sendMessage("§cОшибка отправки в Telegram: ${e.message}")
            plugin.logger.warning("[TgSend] Ошибка: ${e.message}")
            return true
        }

        if (!silent) {
            sender.sendMessage("§aСообщение отправлено в чат '${chat.name}' ($chatId)" +
                (if (replyToMessageId != null) " (ответ на сообщение $replyToMessageId)" else ""))
        }
        return true
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§6=== TgSend Command Help ===")
        sender.sendMessage("§e/tgsend <bc|bcs> <чат> <формат> [reply_to=ID] <сообщение>")
        sender.sendMessage("§7")
        sender.sendMessage("§7Форматы:")
        sender.sendMessage("§7  plain    - обычный текст")
        sender.sendMessage("§7  html     - HTML теги")
        sender.sendMessage("§7  markdown - Markdown стиль")
        sender.sendMessage("§7  mm       - MiniMessage формат")
        sender.sendMessage("§7  json     - Adventure JSON формат")
        sender.sendMessage("§7")
        sender.sendMessage("§7Опции:")
        sender.sendMessage("§7  bc       - с уведомлением")
        sender.sendMessage("§7  bcs      - без уведомления (silent)")
        sender.sendMessage("§7  reply_to=ID - ответ на сообщение в Telegram")
        sender.sendMessage("§7")
        sender.sendMessage("§7Примеры:")
        sender.sendMessage("§7  /tgsend bc global html <b>Привет</b>")
        sender.sendMessage("§7  /tgsend bcs -1001234567890_12345 markdown **Жирный**")
        sender.sendMessage("§7  /tgsend bc helpchat markdown reply_to=12345 > Цитата%0AОтвет")
    }

    private fun findChat(nameOrId: String): ChatConfig? {
        // 1. Поиск по имени в game_chats
        val byName = ZTele.chatManager.getChat(nameOrId)
        if (byName != null) return byName

        // 2. Парсинг как ID (с или без топика)
        val parts = nameOrId.split("_")
        val chatId = parts[0].toLongOrNull() ?: return null
        val topicId = parts.getOrNull(1)?.toIntOrNull() ?: 0
        
        // 3. Поиск в game_chats по ID
        val existing = ZTele.chatManager.getChatByTelegramId(chatId, topicId)
        if (existing != null) return existing
        
        // 4. Создаём временный чат
        return ChatConfig(
            name = nameOrId,
            chatId = chatId,
            topicId = topicId,
            enabled = true
        )
    }

    /**
     * Конвертация Markdown в HTML для Telegram
     */
    private fun markdownToTelegramHtml(text: String): String {
        var result = text
        
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
        result = result.replace(Regex("__([^_]+)__"), "<b>$1</b>")
        result = result.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)"), "<i>$1</i>")
        result = result.replace(Regex("(?<!_)_([^_]+)_(?!_)"), "<i>$1</i>")
        result = result.replace(Regex("\\+\\+([^+]+)\\+\\+"), "<u>$1</u>")
        result = result.replace(Regex("~~([^~]+)~~"), "<s>$1</s>")
        result = result.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        result = result.replace(Regex("```([^`]+)```", RegexOption.DOT_MATCHES_ALL), "<pre>$1</pre>")
        result = result.replace(Regex("```([a-zA-Z0-9_+-]+)\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)) { match ->
            "<pre><code class=\"language-${match.groupValues[1]}\">${match.groupValues[2]}</code></pre>"
        }
        result = result.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
        result = result.replace(Regex("^> (.+)$", RegexOption.MULTILINE), "<blockquote>$1</blockquote>")
        result = result.replace(Regex("\\|\\|([^|]+)\\|\\|"), "<span class=\"tg-spoiler\">$1</span>")
        
        return result
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun validateAndSanitizeHtml(html: String): String {
        var result = html
        result = result.replace(Regex("""\s+on\w+\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("""href\s*=\s*["']\s*javascript:""", RegexOption.IGNORE_CASE), "href=\"#\"")
        return result
    }

    private fun sendTelegramMessage(chatId: String, message: String, replyToMessageId: Int?) {
        val (baseChatId, threadId) = parseChatId(chatId)
        
        val sendMessage = SendMessage()
        sendMessage.chatId = baseChatId
        sendMessage.text = message
        sendMessage.parseMode = "HTML"
        
        if (threadId != null) {
            sendMessage.messageThreadId = threadId
        }
        
        if (replyToMessageId != null && replyToMessageId > 0) {
            sendMessage.replyToMessageId = replyToMessageId
        }
        
        ZTele.bot.execute(sendMessage)
    }

    private fun parseChatId(chatId: String): Pair<String, Int?> {
        return if (chatId.contains("_")) {
            val parts = chatId.split("_")
            Pair(parts[0], parts.getOrNull(1)?.toIntOrNull())
        } else {
            Pair(chatId, null)
        }
    }
}
