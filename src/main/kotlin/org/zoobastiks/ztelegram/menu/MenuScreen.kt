package org.zoobastiks.ztelegram.menu

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton

/**
 * Базовый класс для экранов меню
 */
sealed class MenuScreen {
    abstract val text: String
    abstract val keyboard: InlineKeyboardMarkup
    
    /**
     * Создает кнопку для inline keyboard
     */
    protected fun createButton(text: String, callbackData: String): InlineKeyboardButton {
        val button = InlineKeyboardButton()
        button.text = text
        button.callbackData = callbackData
        return button
    }
    
    /**
     * Создает клавиатуру из списка строк кнопок
     */
    protected fun createKeyboard(rows: List<List<InlineKeyboardButton>>): InlineKeyboardMarkup {
        val keyboard = InlineKeyboardMarkup()
        // Убеждаемся, что keyboard всегда инициализирован (не null)
        keyboard.keyboard = rows.ifEmpty { emptyList() }
        return keyboard
    }
}

/**
 * Главное меню
 */
class MainMenuScreen(
    private val menuText: String,
    private val userId: Long,
    private val isAdmin: Boolean = false
) : MenuScreen() {
    override val text: String = menuText
    
    override val keyboard: InlineKeyboardMarkup = createKeyboard(
        buildList {
            add(listOf(createButton("🎲 Рулетка", CallbackData.RANDOM_MENU.withUserId(userId))))
			add(listOf(createButton("🎯 Отметиться", CallbackData.CHECKIN.withUserId(userId))))
            add(listOf(createButton("👥 Онлайн", CallbackData.ONLINE.withUserId(userId))))
            add(listOf(createButton("⚡ TPS", CallbackData.TPS.withUserId(userId))))
            add(listOf(createButton("📊 Статистика", CallbackData.STATS_MENU.withUserId(userId))))
            add(listOf(createButton("👤 Игрок", CallbackData.PLAYER_MENU.withUserId(userId))))
            add(listOf(createButton("⭐ Репутация", CallbackData.REP_MENU.withUserId(userId))))
            add(listOf(createButton("💸 Переводы", CallbackData.PAYMENT_MENU.withUserId(userId))))
            if (isAdmin) {
                add(listOf(createButton("🛡️ Администрация", CallbackData.STAFF_LIST.withUserId(userId))))
                add(listOf(createButton("⚙️ Настройки", CallbackData.SETTINGS_MENU.withUserId(userId))))
            }
            add(listOf(createButton("ℹ️ Информация", CallbackData.INFO_MENU.withUserId(userId))))
            add(listOf(createButton("🔙 Закрыть", CallbackData.CLOSE.withUserId(userId))))
        }
    )
}

/**
 * Меню рулетки
 */
class RandomMenuScreen(
    private val menuText: String,
    private val canStart: Boolean,
    private val cooldownTime: String?,
    private val userId: Long
) : MenuScreen() {
    override val text: String = buildString {
        append(menuText)
        if (!canStart && cooldownTime != null) {
            append("\n\n⏳ Осталось времени: $cooldownTime")
        }
    }
    
    override val keyboard: InlineKeyboardMarkup = createKeyboard(
        buildList {
            if (canStart) {
                add(listOf(createButton("▶ Запустить рулетку", CallbackData.RANDOM_START.withUserId(userId))))
            } else {
                add(listOf(createButton("⏳ Проверить кулдаун", CallbackData.RANDOM_CHECK_COOLDOWN.withUserId(userId))))
            }
            add(listOf(createButton("📋 Список наград", CallbackData.RANDOM_REWARDS.withUserId(userId))))
            add(listOf(createButton("🔙 Назад в меню", CallbackData.MAIN_MENU.withUserId(userId))))
        }
    )
}

/**
 * Меню статистики
 */
class StatsMenuScreen(
    private val menuText: String,
    private val userId: Long
) : MenuScreen() {
    override val text: String = menuText
    
    override val keyboard: InlineKeyboardMarkup = createKeyboard(
        listOf(
            listOf(createButton("📈 Статистика за сегодня", CallbackData.STATS_TODAY.withUserId(userId))),
            listOf(createButton("📊 Топ игроков", CallbackData.STATS_TOP.withUserId(userId))),
            listOf(createButton("💰 Топ по балансу", CallbackData.STATS_TOP_BAL.withUserId(userId))),
            listOf(createButton("🔙 Назад в меню", CallbackData.MAIN_MENU.withUserId(userId)))
        )
    )
}

/**
 * Меню настроек
 */
class SettingsMenuScreen(
    private val menuText: String,
    private val userId: Long
) : MenuScreen() {
    override val text: String = menuText
    
    override val keyboard: InlineKeyboardMarkup = createKeyboard(
        listOf(
            listOf(createButton("🔙 Назад в меню", CallbackData.MAIN_MENU.withUserId(userId)))
        )
    )
}

/**
 * Меню информации
 */
class InfoMenuScreen(
    private val menuText: String,
    private val userId: Long
) : MenuScreen() {
    override val text: String = menuText
    
    override val keyboard: InlineKeyboardMarkup = createKeyboard(
        listOf(
            listOf(createButton("📋 Список команд", CallbackData.INFO_COMMANDS.withUserId(userId))),
            listOf(createButton("🔗 Ссылки", CallbackData.INFO_LINKS.withUserId(userId))),
            listOf(createButton("🖥️ О сервере", CallbackData.INFO_SERVER.withUserId(userId))),
            listOf(createButton("🔙 Назад в меню", CallbackData.MAIN_MENU.withUserId(userId)))
        )
    )
}

/**
 * Меню репутации
 */
class RepMenuScreen(
    private val menuText: String,
    private val userId: Long
) : MenuScreen() {
    override val text: String = menuText
    
    override val keyboard: InlineKeyboardMarkup = createKeyboard(
        listOf(
            listOf(createButton("🏆 Топ игроков", CallbackData.REP_TOP.withUserId(userId))),
            listOf(createButton("📜 Последние изменения", CallbackData.REP_RECENT.withUserId(userId))),
            listOf(createButton("🔙 Назад в меню", CallbackData.MAIN_MENU.withUserId(userId)))
        )
    )
}

/**
 * Меню рестарта (только для администраторов)
 */
class RestartMenuScreen(
    private val menuText: String,
    private val userId: Long
) : MenuScreen() {
    override val text: String = menuText
    
    override val keyboard: InlineKeyboardMarkup = createKeyboard(
        listOf(
            listOf(createButton("🔄 Мгновенный рестарт", CallbackData.RESTART_NOW.withUserId(userId))),
            listOf(createButton("❌ Отменить рестарт", CallbackData.RESTART_CANCEL.withUserId(userId))),
            listOf(createButton("🔙 Назад в меню", CallbackData.MAIN_MENU.withUserId(userId)))
        )
    )
}

/**
 * Меню списка администрации
 */
class StaffListMenuScreen(
    private val headerText: String,
    private val players: List<org.zoobastiks.ztelegram.conf.TConf.StaffPlayer>,
    private val playerFormat: String,
    private val userId: Long
) : MenuScreen() {
    override val text: String = buildString {
        // Добавляем заголовок только если он не пустой
        if (headerText.isNotEmpty()) {
            append(headerText)
        }
        
        players.forEachIndexed { _, player ->
            // Если формат пустой, пропускаем этого игрока в тексте
            if (playerFormat.isNotEmpty()) {
                val formatted = playerFormat
                    .replace("%rank%", player.rank)
                    .replace("%telegram%", player.telegram)
                    .replace("%name%", player.name)
                    .replace("%nickname%", player.nickname)
                
                // Добавляем форматированный текст только если он не пустой после замены
                if (formatted.isNotEmpty()) {
                    if (isNotEmpty()) {
                        append("\n\n")
                    }
                    append(formatted)
                }
            }
        }
        
        // Если текст пустой, добавляем минимальное сообщение
        if (isEmpty()) {
            append("👥 **СПИСОК АДМИНИСТРАЦИИ**")
        }
    }
    
    override val keyboard: InlineKeyboardMarkup = createKeyboard(
        buildList {
            players.forEachIndexed { index, player ->
                val buttons = mutableListOf<InlineKeyboardButton>()
                
                // Кнопка с никнеймом для информации
                buttons.add(createButton(
                    player.nickname,
                    "${CallbackData.STAFF_INFO}:${index}".withUserId(userId)
                ))
                
                player.actions.forEach { action ->
                    if (action.enabled) {
                        when (action.type) {
                            "write" -> {
                                buttons.add(createButton(
                                    "✉️ Написать",
                                    "${CallbackData.STAFF_WRITE}:${index}".withUserId(userId)
                                ))
                            }
                            "ticket" -> {
                                buttons.add(createButton(
                                    "🎫 Тикет",
                                    "${CallbackData.STAFF_TICKET}:${index}".withUserId(userId)
                                ))
                            }
                        }
                    }
                }
                
                if (buttons.isNotEmpty()) {
                    // Разбиваем кнопки на строки по 2 кнопки
                    buttons.chunked(2).forEach { row ->
                        add(row)
                    }
                }
            }
            add(listOf(createButton("🔙 Назад в меню", CallbackData.MAIN_MENU.withUserId(userId))))
        }
    )
}

/**
 * Меню переводов денег
 */
class PaymentMenuScreen(
    private val menuText: String,
    private val userId: Long
) : MenuScreen() {
    override val text: String = menuText
    
    override val keyboard: InlineKeyboardMarkup = createKeyboard(
        buildList {
            add(listOf(createButton("💸 Перевести монеты", CallbackData.PAYMENT_TRANSFER.withUserId(userId))))
            add(listOf(createButton("📜 История переводов", CallbackData.PAYMENT_HISTORY.withUserId(userId))))
            add(listOf(createButton("🔙 Назад в меню", CallbackData.MAIN_MENU.withUserId(userId))))
        }
    )
}

/**
 * Экран истории переводов
 */
class PaymentHistoryScreen(
    private val menuText: String,
    private val history: List<org.zoobastiks.ztelegram.mgr.PaymentManager.PaymentRecord>,
    private val stats: org.zoobastiks.ztelegram.mgr.PaymentManager.PaymentStats,
    private val playerName: String,
    private val userId: Long
) : MenuScreen() {
    override val text: String = buildString {
        append(menuText.replace("%user%", playerName))
        append("\n\n")
        
        // Статистика
        val economy = org.zoobastiks.ztelegram.ZTele.economy
        val currency = economy?.currencyNamePlural() ?: "монет"
        append("📊 **Статистика:**\n")
        append("💰 Отправлено: **${String.format("%.2f", stats.totalSent)}** $currency (${stats.sentCount} переводов)\n")
        append("💵 Получено: **${String.format("%.2f", stats.totalReceived)}** $currency (${stats.receivedCount} переводов)\n")
        
        if (history.isNotEmpty()) {
            append("\n📜 **Последние переводы:**\n")
            history.take(10).forEach { record ->
                val isSent = record.fromPlayer.equals(playerName, ignoreCase = true)
                val otherPlayer = if (isSent) record.toPlayer else record.fromPlayer
                val direction = if (isSent) "➡️" else "⬅️"
                val amount = String.format("%.2f", record.amount)
                
                // Форматируем время
                val timeStr = try {
                    val dateTime = java.time.LocalDateTime.parse(record.timestamp, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    dateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                } catch (e: Exception) {
                    record.timestamp
                }
                
                append("\n$direction **$otherPlayer** - $amount $currency\n")
                append("   📅 $timeStr\n")
            }
        } else {
            append("\n📭 История переводов пуста")
        }
    }
    
    override val keyboard: InlineKeyboardMarkup = createKeyboard(
        buildList {
            add(listOf(createButton("🔙 Назад к переводам", CallbackData.PAYMENT_MENU.withUserId(userId))))
            add(listOf(createButton("🔙 Назад в меню", CallbackData.MAIN_MENU.withUserId(userId))))
        }
    )
}

/**
 * Callback data идентификаторы для кнопок
 * Формат: "menu:action:userId" для проверки владельца
 */
object CallbackData {
    // Главное меню
    const val MAIN_MENU = "menu:main"
    const val CLOSE = "menu:close"
    
    // Рулетка
    const val RANDOM_MENU = "menu:random"
    const val RANDOM_START = "menu:random:start"
    const val RANDOM_CHECK_COOLDOWN = "menu:random:cooldown"
    const val RANDOM_REWARDS = "menu:random:rewards"
    const val RANDOM_REWARDS_PAGE = "menu:random:rewards:page"
    
	// Отметится
	const val CHECKIN = "menu:checkin"
	
    // Онлайн
    const val ONLINE = "menu:online"
    
    // TPS
    const val TPS = "menu:tps"
    
    // Статистика
    const val STATS_MENU = "menu:stats"
    const val STATS_TODAY = "menu:stats:today"
    const val STATS_TOP = "menu:stats:top"
    const val STATS_TOP_BAL = "menu:stats:topbal"
    
    // Игрок
    const val PLAYER_MENU = "menu:player"
    const val PLAYER_LIST_REGISTERED = "menu:player:list:registered"
    const val PLAYER_LIST_ONLINE = "menu:player:list:online"
    const val PLAYER_SELECT = "menu:player:select"
    
    // Регистрация
    const val REGISTER_MENU = "register:menu"
    const val REGISTER_START = "register:start"
    const val REGISTER_UNREGISTER = "register:unregister"
    const val REGISTER_UNREGISTER_CONFIRM = "register:unregister:confirm"
    const val REGISTER_LIST = "register:list"
    const val REGISTER_LIST_PAGE = "register:list:page"
    const val REGISTER_INFO = "register:info"
    const val REGISTER_REWARDS = "register:rewards"
    const val REGISTER_LINK_ACCOUNT = "register:link"
    const val REGISTER_LINK_ACCOUNT_GENERATE = "register:link:generate"
    
    // Репутация
    const val REP_MENU = "menu:rep"
    const val REP_TOP = "menu:rep:top"
    const val REP_RECENT = "menu:rep:recent"
    
    // Рестарт (старые, оставляем для совместимости)
    const val RESTART_MENU = "menu:restart"
    const val RESTART_NOW = "menu:restart:now"
    const val RESTART_CANCEL = "menu:restart:cancel"
    
    // Настройки (новое меню)
    const val SETTINGS_MENU = "menu:settings"
    const val SETTINGS_RESTART = "menu:settings:restart"
    const val SETTINGS_PLAYERS = "menu:settings:players"
    const val SETTINGS_WEATHER = "menu:settings:weather"
    const val SETTINGS_TIME = "menu:settings:time"
    const val SETTINGS_SERVER = "menu:settings:server"
    
    // Рестарт (в настройках)
    const val SETTINGS_RESTART_NOW = "menu:settings:restart:now"
    const val SETTINGS_RESTART_5MIN = "menu:settings:restart:5min"
    const val SETTINGS_RESTART_CANCEL = "menu:settings:restart:cancel"
    
    // Управление игроками
    const val SETTINGS_PLAYER_SELECT = "menu:settings:player:select"
    const val SETTINGS_PLAYER_KICK = "menu:settings:player:kick"
    const val SETTINGS_PLAYER_BAN_10MIN = "menu:settings:player:ban10min"
    const val SETTINGS_PLAYER_KILL = "menu:settings:player:kill"
    
    // Погода
    const val SETTINGS_WEATHER_CLEAR = "menu:settings:weather:clear"
    const val SETTINGS_WEATHER_RAIN = "menu:settings:weather:rain"
    const val SETTINGS_WEATHER_THUNDER = "menu:settings:weather:thunder"
    
    // Время
    const val SETTINGS_TIME_DAY = "menu:settings:time:day"
    const val SETTINGS_TIME_NIGHT = "menu:settings:time:night"
    const val SETTINGS_TIME_NOON = "menu:settings:time:noon"
    const val SETTINGS_TIME_MIDNIGHT = "menu:settings:time:midnight"
    
    // Сервер
    const val SETTINGS_SERVER_RELOAD = "menu:settings:server:reload"
    const val SETTINGS_SERVER_STOP = "menu:settings:server:stop"
    
    // Информация
    const val INFO_MENU = "menu:info"
    const val INFO_COMMANDS = "menu:info:commands"
    const val INFO_LINKS = "menu:info:links"
    const val INFO_SERVER = "menu:info:server"
    
    // Список администрации
    const val STAFF_LIST = "menu:staff"
    const val STAFF_PLAYER = "menu:staff:player"
    const val STAFF_WRITE = "menu:staff:write"
    const val STAFF_TICKET = "menu:staff:ticket"
    const val STAFF_INFO = "menu:staff:info"
    
    // Переводы денег
    const val PAYMENT_MENU = "menu:payment"
    const val PAYMENT_HISTORY = "menu:payment:history"
    const val PAYMENT_TRANSFER = "menu:payment:transfer"
    const val PAYMENT_TRANSFER_SELECT = "menu:payment:transfer:select"
    const val PAYMENT_TRANSFER_AMOUNT = "menu:payment:transfer:amount"
    const val PAYMENT_TRANSFER_CONFIRM = "menu:payment:transfer:confirm"
    
    /**
     * Извлекает action и userId из callback_data
     */
    fun parseCallbackData(data: String): Pair<String, Long?> {
        val parts = data.split(":")
        if (parts.size >= 3) {
            val userId = parts.last().toLongOrNull()
            val action = parts.dropLast(1).joinToString(":")
            return Pair(action, userId)
        }
        return Pair(data, null)
    }
}

/**
 * Добавляет userId к callback_data для проверки владельца
 */
fun String.withUserId(userId: Long): String {
    return "$this:$userId"
}

