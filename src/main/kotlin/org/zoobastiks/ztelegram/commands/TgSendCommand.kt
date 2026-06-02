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
 * 
 * @param bc - отправлять с уведомлением отправителя
 * @param bcs - отправлять без уведомления (silent mode)
 * @param чат - имя чата из game_chats или Telegram ID (например -1001234567890 или -1001234567890_12345)
 * @param формат - plain, mm, html, markdown, json
 * @param reply_to=ID - (опционально) ID сообщения в Telegram, на которое отвечать
 * @param сообщение - текст сообщения
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

        // Находим чат
        val chat = findChat(chatNameOrId)
        if (chat == null) {
            if (!silent) sender.sendMessage("§cЧат '$chatNameOrId' не найден")
            return true
        }

        val chatId = if (chat.topicId > 0) "${chat.chatId}_${chat.topicId}" else chat.chatId.toString()

        // Проверка: если указан ID с топиком, но в итоге chatId без топика — ОШИБКА!
        val originalHasTopic = chatNameOrId.contains("_")
        val resultHasTopic = chatId.contains("_")
        
        if (originalHasTopic && !resultHasTopic) {
            val errorMsg = "§cОшибка: указан топик '$chatNameOrId', но чат преобразован в '$chatId' без топика. Возможно, топик не существует."
            if (!silent) sender.sendMessage(errorMsg)
            plugin.logger.warning("[TgSend] $errorMsg")
            return true
        }

        // ========== ОБРАБОТКА ПЕРЕНОСОВ СТРОК ==========
        var processedMessage = rawMessage
        
        // URL-encoded перенос
        processedMessage = processedMessage.replace("%0A", "\n", ignoreCase = true)
        
        // Экранированный перенос
        processedMessage = processedMessage.replace("\\n", "\n")
        
        // MiniMessage теги для переноса строки (полезно при использовании mm формата)
        processedMessage = processedMessage.replace("<newLine>", "\n", ignoreCase = true)
        processedMessage = processedMessage.replace("<nl>", "\n", ignoreCase = true)
        processedMessage = processedMessage.replace("</newLine>", "\n", ignoreCase = true)
        processedMessage = processedMessage.replace("</nl>", "\n", ignoreCase = true)
        // ========== КОНЕЦ ОБРАБОТКИ ==========

        // Обработка формата и конвертация в HTML для Telegram
        val finalHtmlMessage = when (format) {
            "plain" -> {
                // Обычный текст — экранируем HTML-символы
                escapeHtml(processedMessage)
            }
            "mm" -> {
                // MiniMessage → Plain Text → экранируем HTML
                try {
                    val component = MiniMessage.miniMessage().deserialize(processedMessage)
                    val plainText = PlainTextComponentSerializer.plainText().serialize(component)
                    escapeHtml(plainText)
                } catch (e: Exception) {
                    if (!silent) sender.sendMessage("§cОшибка парсинга MiniMessage: ${e.message}")
                    return true
                }
            }
            "markdown" -> {
                // Markdown → Telegram HTML
                markdownToTelegramHtml(processedMessage)
            }
            "html" -> {
                // Прямая передача HTML — доверяем, но проверяем базовые теги
                validateAndSanitizeHtml(processedMessage)
            }
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

        // Заменяем плейсхолдеры через PlaceholderEngine
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
            if (ZTele.conf.debugEnabled) {
                plugin.logger.info("📤 [TgSend] Отправлено в $chatId" + 
                    (if (replyToMessageId != null) " (reply to $replyToMessageId)" else ""))
            }
        } catch (e: Exception) {
            if (!silent) sender.sendMessage("§cОшибка отправки в Telegram: ${e.message}")
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
        sender.sendMessage("§7  html     - HTML теги (<b>, <i>, <code>, <a>, <pre>, <blockquote>, <span class=\"tg-spoiler\">)")
        sender.sendMessage("§7  markdown - Markdown стиль (**жирный**, *курсив*, `код`, ~~зачёркнутый~~, ||спойлер||, > цитата, [ссылка](url))")
        sender.sendMessage("§7  mm       - MiniMessage формат (для совместимости)")
        sender.sendMessage("§7  json     - Adventure JSON формат")
        sender.sendMessage("§7")
        sender.sendMessage("§7Опции:")
        sender.sendMessage("§7  bc       - с уведомлением отправителя")
        sender.sendMessage("§7  bcs      - без уведомления (silent)")
        sender.sendMessage("§7  reply_to=ID - ответ на конкретное сообщение в Telegram")
        sender.sendMessage("§7")
        sender.sendMessage("§7Примеры:")
        sender.sendMessage("§7  /tgsend bc global html <b>Привет</b>")
        sender.sendMessage("§7  /tgsend bcs -1001234567890_12345 markdown **Жирный** и *курсив*")
        sender.sendMessage("§7  /tgsend bc helpchat markdown reply_to=12345 > Цитата%0AОтвет на неё")
    }

    private fun findChat(nameOrId: String): ChatConfig? {
        // 1. Поиск по имени в game_chats
        val byName = ZTele.chatManager.getChat(nameOrId)
        if (byName != null) return byName

        // 2. Парсинг ID
        val parts = nameOrId.split("_")
        val chatId = parts[0].toLongOrNull() ?: return null
        val topicId = parts.getOrNull(1)?.toIntOrNull() ?: 0
        
        // 3. Поиск в game_chats по ID
        val existing = ZTele.chatManager.getChatByTelegramId(chatId, topicId)
        if (existing != null) return existing
        
        // 4. Создаём временный чат с точным сохранением topicId
        return ChatConfig(
            name = nameOrId,
            chatId = chatId,
            topicId = topicId,
            enabled = true
        )
    }

    /**
     * Конвертация Markdown-подобного синтаксиса в HTML для Telegram
     * Поддерживает:
     * **жирный** → <b>жирный</b>
     * *курсив* или _курсив_ → <i>курсив</i>
     * __подчёркнутый__ → <u>подчёркнутый</u>
     * ~~зачёркнутый~~ → <s>зачёркнутый</s>
     * `моноширинный` → <code>моноширинный</code>
     * ```блок кода``` → <pre>блок кода</pre>
     * ```python\ncode\n``` → <pre><code class="language-python">code</code></pre>
     * [текст](url) → <a href="url">текст</a>
     * > цитата → <blockquote>цитата</blockquote>
     * ||спойлер|| → <span class="tg-spoiler">спойлер</span>
     */
    private fun markdownToTelegramHtml(text: String): String {
        var result = text
        
        // Экранируем HTML-символы в обычном тексте (но не внутри уже существующих тегов)
        // Делаем это аккуратно, чтобы не поломать Markdown
        
        // 1. Жирный: **text** → <b>text</b> (не пересекается с другими)
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
        
        // 2. Жирный: __text__ → <b>text</b> (альтернативный вариант, но не конфликтует с подчёркиванием)
        result = result.replace(Regex("__([^_]+)__"), "<b>$1</b>")
        
        // 3. Курсив: *text* → <i>text</i> (только если не часть **)
        result = result.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)"), "<i>$1</i>")
        
        // 4. Курсив: _text_ → <i>text</i>
        result = result.replace(Regex("(?<!_)_([^_]+)_(?!_)"), "<i>$1</i>")
        
        // 5. Подчёркнутый: ++text++ → <u>text</u>
        result = result.replace(Regex("\\+\\+([^+]+)\\+\\+"), "<u>$1</u>")
        
        // 6. Зачёркнутый: ~~text~~ → <s>text</s>
        result = result.replace(Regex("~~([^~]+)~~"), "<s>$1</s>")
        
        // 7. Моноширинный (inline): `text` → <code>text</code>
        result = result.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        
        // 8. Моноширинный блок: ```text``` → <pre>text</pre>
        result = result.replace(Regex("```([^`]+)```", RegexOption.DOT_MATCHES_ALL), "<pre>$1</pre>")
        
        // 9. Код с языком: ```python\ncode\n``` → <pre><code class="language-python">code</code></pre>
        result = result.replace(Regex("```([a-zA-Z0-9_+-]+)\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)) { match ->
            val language = match.groupValues[1]
            val code = match.groupValues[2]
            "<pre><code class=\"language-$language\">$code</code></pre>"
        }
        
        // 10. Ссылка: [text](url) → <a href="url">text</a>
        result = result.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
        
        // 11. Цитата: > text → <blockquote>text</blockquote> (поддерживаем многострочные)
        result = result.replace(Regex("^> (.+)$", RegexOption.MULTILINE), "<blockquote>$1</blockquote>")
        
        // 12. Скрытый текст (спойлер): ||text|| → <span class="tg-spoiler">text</span>
        result = result.replace(Regex("\\|\\|([^|]+)\\|\\|"), "<span class=\"tg-spoiler\">$1</span>")
        
        // 13. Дополнительно: обработка переносов строк в цитатах и других блоках
        // (оставляем \n как есть — Telegram их поддерживает)
        
        return result
    }

    /**
     * Экранирование HTML-спецсимволов для безопасной отправки в Telegram
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    /**
     * Валидация и санитизация HTML для Telegram
     * Разрешаем только безопасные теги, поддерживаемые Telegram API
     */
    private fun validateAndSanitizeHtml(html: String): String {
        // Разрешённые теги и их атрибуты
        val allowedTags = mapOf(
            "b" to emptyList(),
            "strong" to emptyList(),
            "i" to emptyList(),
            "em" to emptyList(),
            "u" to listOf(),
            "ins" to listOf(),
            "s" to emptyList(),
            "strike" to emptyList(),
            "del" to emptyList(),
            "code" to emptyList(),
            "pre" to listOf(),
            "a" to listOf("href"),
            "blockquote" to emptyList(),
            "span" to listOf("class")
        )
        
        // Простая санитизация — удаляем неразрешённые теги
        var result = html
        
        // Удаляем небезопасные атрибуты (javascript: и т.д.)
        result = result.replace(Regex("""\s+on\w+\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE), "")
        
        // Удаляем опасные протоколы в ссылках
        result = result.replace(Regex("""href\s*=\s*["']\s*javascript:""", RegexOption.IGNORE_CASE), "href=\"#\"")
        
        return result
    }

    /**
     * Отправка сообщения в Telegram с поддержкой reply_to_message_id
     */
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

    /**
     * Разбирает chatId на базовый ID чата и ID темы
     */
    private fun parseChatId(chatId: String): Pair<String, Int?> {
        return if (chatId.contains("_")) {
            val parts = chatId.split("_")
            val baseChatId = parts[0]
            val threadId = parts[1].toIntOrNull()
            Pair(baseChatId, threadId)
        } else {
            Pair(chatId, null)
        }
    }
}
