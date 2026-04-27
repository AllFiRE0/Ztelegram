package org.zoobastiks.ztelegram.bot

import org.bukkit.Bukkit
import org.json.JSONObject
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import java.io.ByteArrayInputStream
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.zoobastiks.ztelegram.renderer.ItemRenderer
import org.zoobastiks.ztelegram.renderer.InventoryRenderer
import org.zoobastiks.ztelegram.renderer.EnderChestRenderer
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import org.zoobastiks.ztelegram.ZTele
import org.zoobastiks.ztelegram.utils.PlaceholderProcessor
import org.zoobastiks.ztelegram.utils.PlaceholderEngine
import org.zoobastiks.ztelegram.utils.TopPlaceholderProcessor
import org.zoobastiks.ztelegram.utils.TopManager
import org.zoobastiks.ztelegram.conf.TConf
import org.zoobastiks.ztelegram.mgr.PMgr
import org.zoobastiks.ztelegram.ColorUtils
import org.zoobastiks.ztelegram.GradientUtils
import org.zoobastiks.ztelegram.stats.StatsManager
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.NoRouteToHostException
import java.net.SocketException
import javax.net.ssl.SSLException

class TBot(private val plugin: ZTele) : TelegramLongPollingBot(plugin.config.getString("bot.token") ?: "") {
    // Используем геттер для всегда актуального конфига
    private val conf: TConf
        get() = ZTele.conf
    private val mgr: PMgr
        get() = ZTele.mgr

    // Контекст для хранения текущего chatId (для ответов в ту же тему)
    private val currentChatIdContext = ThreadLocal<String>()
    private var botsApi: TelegramBotsApi? = null
    private val miniMessage = MiniMessage.miniMessage()
    private var botSession: DefaultBotSession? = null

    // === СИСТЕМА ЗАЩИТЫ ОТ СПАМА ОШИБОК И НАДЕЖНОГО ПЕРЕПОДКЛЮЧЕНИЯ ===
    private val connectionState = AtomicBoolean(false) // true = подключен, false = отключен
    private val lastErrorTime = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val lastLogTime = ConcurrentHashMap<String, Long>()

    // Настройки логирования ошибок
    private val ERROR_LOG_COOLDOWN = 60_000L // 1 минута между логами одной и той же ошибки
    private val MAX_ERRORS_PER_MINUTE = 3 // Максимум 3 ошибки в минуту в лог

    // Расширенный список сетевых ошибок для умной обработки
    private val NETWORK_ERRORS = setOf(
        "Connection pool shut down",
        "Connection pool is closed",
        "Connection pool has been shut down",
        "Pool is closed",
        "Connection refused",
        "UnknownHostException", // DNS проблемы
        "api.telegram.org",     // Проблемы с Telegram API
        "ConnectException",     // Общие ошибки подключения
        "SocketTimeoutException", // Таймауты
        "NoRouteToHostException", // Сетевые проблемы
        "SocketException",      // Сетевые сокеты
        "SSLException",         // SSL проблемы
        "ReadTimeoutException", // Таймауты чтения
        "Connection timed out", // Таймауты подключения
        "Network is unreachable", // Недоступность сети
        "Host is unreachable"   // Недоступность хоста
    )

    // Менеджер переподключений с экспоненциальной задержкой
    private val reconnectionManager = ReconnectionManager()

    // === ПРОФЕССИОНАЛЬНЫЙ МЕНЕДЖЕР ПЕРЕПОДКЛЮЧЕНИЙ ===
    inner class ReconnectionManager {
        private val reconnectScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ZTelegram-Reconnection-Thread").apply {
                isDaemon = true
            }
        }

        private val isReconnecting = AtomicBoolean(false)
        private val reconnectAttempt = AtomicLong(0)
        private val consecutiveFailures = AtomicLong(0)
        private val lastSuccessfulConnection = AtomicLong(System.currentTimeMillis())

        // Умная экспоненциальная задержка с джиттером
        private val baseDelaySeconds = arrayOf(5L, 10L, 30L, 60L, 120L, 300L) // 5с, 10с, 30с, 1м, 2м, 5м
        private val maxDelaySeconds = 300L // Максимум 5 минут
        private val maxReconnectAttempts = 20 // Максимум попыток до остановки

        fun scheduleReconnection(errorType: String = "UNKNOWN") {
            if (!isReconnecting.compareAndSet(false, true)) {
                plugin.logger.info("Переподключение уже запланировано, пропускаем...")
                return
            }

            val attempt = reconnectAttempt.incrementAndGet()
            val failures = consecutiveFailures.incrementAndGet()

            // Если превышено максимальное количество попыток
            if (attempt > maxReconnectAttempts) {
                plugin.logger.severe("Maximum reconnection attempts reached ($maxReconnectAttempts). Disabling automatic retries.")
                isReconnecting.set(false)
                return
            }

            // Рассчитываем интеллектуальную задержку
            val delaySeconds = calculateSmartDelay(attempt, failures, errorType)

            plugin.logger.warning("Failed to connect to api.telegram.org ($errorType). Retry attempt #$attempt in $delaySeconds seconds...")

            // Планируем переподключение
            reconnectScheduler.schedule({
                attemptReconnection()
            }, delaySeconds, TimeUnit.SECONDS)
        }

        private fun calculateSmartDelay(attempt: Long, @Suppress("UNUSED_PARAMETER") failures: Long, errorType: String): Long {
            // Базовая задержка в зависимости от номера попытки
            val baseIndex = minOf(attempt.toInt() - 1, baseDelaySeconds.size - 1)
            var delay = baseDelaySeconds[baseIndex]

            // Учитываем тип ошибки для корректировки задержки
            when (errorType) {
                "DNS", "UnknownHostException" -> {
                    // DNS проблемы могут быть длительными, увеличиваем задержку
                    delay = minOf(delay * 2, maxDelaySeconds)
                }
                "TIMEOUT", "CONNECTION_TIMEOUT" -> {
                    // Для таймаутов используем стандартную задержку
                    delay = delay
                }
                "SSL", "SECURITY" -> {
                    // SSL проблемы часто решаются быстро
                    delay = maxOf(delay / 2, 5L)
                }
            }

            // Добавляем джиттер (случайность ±25%) для предотвращения thundering herd
            val jitter = delay * 0.25 * (Math.random() - 0.5)
            delay = maxOf(5L, minOf(maxDelaySeconds, (delay + jitter).toLong()))

            return delay
        }

        private fun attemptReconnection() {
            try {
                plugin.logger.info("Attempting to restore connection to Telegram API...")

                // Сначала безопасно останавливаем текущее соединение
                stopCurrentConnectionSafely()

                // Даем время на полное закрытие соединения
                Thread.sleep(2000)

                // Пытаемся установить новое соединение
                val reconnectionSuccessful = establishNewConnection()

                if (reconnectionSuccessful) {
                    // Успешное переподключение
                    plugin.logger.info("Connection to Telegram API restored.")
                    onReconnectionSuccess()
                } else {
                    // Неудачное переподключение
                    plugin.logger.warning("Reconnection attempt failed.")
                    onReconnectionFailure()
                }

            } catch (e: Exception) {
                plugin.logger.severe("Critical error during reconnection: ${e.javaClass.simpleName}")
                if (plugin.logger.isLoggable(java.util.logging.Level.FINE)) {
                    plugin.logger.fine("Reconnection error details: ${e.message}")
                }
                onReconnectionFailure()
            }
        }

        private fun stopCurrentConnectionSafely() {
            try {
                // Устанавливаем состояние как неактивное
                connectionState.set(false)

                // Останавливаем текущую сессию если она есть
                botSession?.let { session ->
                    if (session.isRunning) {
                        session.stop()
                    }
                }

                // Очищаем ссылки
                botSession = null
                botsApi = null

                // Прерываем связанные потоки
                cleanupTelegramThreads()

            } catch (e: Exception) {
                plugin.logger.warning("Error during safe connection shutdown: ${e.message}")
            }
        }

        private fun establishNewConnection(): Boolean {
            return try {
                // Создаем новое API и регистрируем бота
                botsApi = TelegramBotsApi(DefaultBotSession::class.java)
                val session = botsApi!!.registerBot(this@TBot)

                if (session is DefaultBotSession) {
                    botSession = session
                    connectionState.set(true)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                plugin.logger.warning("Error creating new connection: ${e.javaClass.simpleName}")
                false
            }
        }

        private fun onReconnectionSuccess() {
            // Сбрасываем счетчики ошибок
            reconnectAttempt.set(0)
            consecutiveFailures.set(0)
            lastSuccessfulConnection.set(System.currentTimeMillis())
            isReconnecting.set(false)

            // Запускаем мониторинг соединения
            connectionMonitor.startMonitoring()

            // Отправляем уведомление в консольный канал (если настроено)
            try {
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    if (conf.consoleChannelEnabled) {
                        sendAutoDeleteMessage(
                            conf.consoleChannelId,
                            "✅ Connection to Telegram API restored",
                            30
                        )
                    }
                }, 20L) // Задержка в 1 секунду
            } catch (e: Exception) {
                // Игнорируем ошибки отправки уведомления
            }
        }

        private fun onReconnectionFailure() {
            isReconnecting.set(false)

            // Планируем следующую попытку только если не достигли лимита
            if (reconnectAttempt.get() < maxReconnectAttempts) {
                scheduleReconnection("RETRY")
            } else {
                plugin.logger.severe("All reconnection attempts exhausted. Manual intervention or plugin restart required.")
            }
        }

        fun shutdown() {
            isReconnecting.set(false)
            try {
                reconnectScheduler.shutdown()
                if (!reconnectScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    reconnectScheduler.shutdownNow()
                }
            } catch (e: Exception) {
                plugin.logger.warning("Ошибка при завершении работы менеджера переподключений: ${e.message}")
            }
        }

        fun isCurrentlyReconnecting(): Boolean = isReconnecting.get()

        fun getReconnectionStats(): Triple<Long, Long, Long> {
            return Triple(
                reconnectAttempt.get(),
                consecutiveFailures.get(),
                lastSuccessfulConnection.get()
            )
        }

        fun resetCounters() {
            reconnectAttempt.set(0)
            consecutiveFailures.set(0)
            lastSuccessfulConnection.set(System.currentTimeMillis())
        }
    }

    // === СИСТЕМА МОНИТОРИНГА ПОДКЛЮЧЕНИЯ ===
    private val connectionMonitor = ConnectionMonitor()

    inner class ConnectionMonitor {
        private val isMonitoring = AtomicBoolean(false)
        private var monitoringTask: Int = -1

        fun startMonitoring() {
            if (isMonitoring.compareAndSet(false, true)) {
                plugin.logger.info("Starting Telegram connection monitoring...")
                monitoringTask = plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable {
                    checkConnectionHealth()
                }, 600L, 600L).taskId // Проверяем каждые 30 секунд
            }
        }

        fun stopMonitoring() {
            if (isMonitoring.compareAndSet(true, false)) {
                if (monitoringTask != -1) {
                    plugin.server.scheduler.cancelTask(monitoringTask)
                    monitoringTask = -1
                }
                plugin.logger.info("Stopped Telegram connection monitoring")
            }
        }

        private fun checkConnectionHealth() {
            try {
                // Проверяем состояние сессии
                val session = botSession
                if (session != null && !session.isRunning && connectionState.get()) {
                    logThrottled("CONNECTION_HEALTH", "Detected inactive bot session, starting recovery...")
                    connectionState.set(false)

                    // Запускаем процесс переподключения через менеджер
                    if (!reconnectionManager.isCurrentlyReconnecting()) {
                        reconnectionManager.scheduleReconnection("SESSION_FAILURE")
                    }
                }

                // Дополнительная проверка: если состояние показывает подключение, но сессии нет
                if (connectionState.get() && (session == null || !session.isRunning)) {
                    logThrottled("CONNECTION_HEALTH", "Detected connection state mismatch, correcting...")
                    connectionState.set(false)

                    if (!reconnectionManager.isCurrentlyReconnecting()) {
                        reconnectionManager.scheduleReconnection("STATE_MISMATCH")
                    }
                }

            } catch (e: Exception) {
                logThrottled("CONNECTION_MONITOR", "Connection monitor error: ${e.javaClass.simpleName}", "WARNING")

                // Если произошла критическая ошибка в мониторе, также планируем переподключение
                if (isNetworkError(e, e.message ?: "")) {
                    connectionState.set(false)
                    if (!reconnectionManager.isCurrentlyReconnecting()) {
                        reconnectionManager.scheduleReconnection("MONITOR_ERROR")
                    }
                }
            }
        }
    }

    // === СИСТЕМА ИНТЕЛЛЕКТУАЛЬНОГО ЛОГИРОВАНИЯ ===
    /**
     * Логирует сообщение с ограничением частоты для предотвращения спама
     * @param errorType тип ошибки для группировки
     * @param message сообщение для логирования
     * @param level уровень логирования (INFO, WARNING, SEVERE)
     */
    private fun logThrottled(errorType: String, message: String, level: String = "INFO") {
        val currentTime = System.currentTimeMillis()
        val lastTime = lastLogTime.getOrDefault(errorType, 0L)

        // Проверяем, прошло ли достаточно времени с последнего лога этого типа
        if (currentTime - lastTime >= ERROR_LOG_COOLDOWN) {
            lastLogTime[errorType] = currentTime

            when (level) {
                "WARNING" -> plugin.logger.warning("[$errorType] $message")
                "SEVERE" -> plugin.logger.severe("[$errorType] $message")
                else -> plugin.logger.info("[$errorType] $message")
            }
        }
    }

    /**
     * Интеллектуальная обработка всех типов сетевых ошибок с защитой от спама
     */
    private fun handleConnectionError(e: Exception, context: String = "Unknown"): Boolean {
        val errorMessage = e.message ?: "Unknown error"
        val errorClass = e.javaClass.simpleName
        val currentTime = System.currentTimeMillis()

        // Определяем тип ошибки для умной обработки
        val errorType = classifyNetworkError(e, errorMessage)
        val isCriticalNetworkError = isNetworkError(e, errorMessage)

        if (isCriticalNetworkError) {
            // Устанавливаем состояние соединения как неактивное
            connectionState.set(false)

            // Логируем с защитой от спама
            logSmartError(errorType, context, errorClass, errorMessage)

            // Обновляем статистику ошибок
            errorCount.incrementAndGet()
            lastErrorTime.set(currentTime)

            // Планируем переподключение с учетом типа ошибки
            if (!reconnectionManager.isCurrentlyReconnecting()) {
                reconnectionManager.scheduleReconnection(errorType)
            }

            return true // Критическая сетевая ошибка
        } else {
            // Для некритических ошибок используем ограниченное логирование
            logThrottled("TELEGRAM_${context}_${errorType}", "$context error ($errorClass): $errorMessage", "WARNING")
            return false
        }
    }

    /**
     * Классифицирует тип сетевой ошибки для оптимальной обработки
     */
    private fun classifyNetworkError(e: Exception, message: String): String {
        return when {
            e is UnknownHostException || message.contains("UnknownHostException", true) ||
            message.contains("api.telegram.org", true) -> "DNS"

            e is ConnectException || message.contains("ConnectException", true) ||
            message.contains("Connection refused", true) -> "CONNECTION_REFUSED"

            e is SocketTimeoutException || message.contains("SocketTimeoutException", true) ||
            message.contains("timed out", true) -> "TIMEOUT"

            e is NoRouteToHostException || message.contains("NoRouteToHostException", true) ||
            message.contains("No route to host", true) -> "ROUTING"

            e is SSLException || message.contains("SSLException", true) -> "SSL"

            e is SocketException || message.contains("SocketException", true) -> "SOCKET"

            message.contains("Connection pool", true) -> "CONNECTION_POOL"

            else -> "NETWORK_GENERIC"
        }
    }

    /**
     * Проверяет, является ли исключение сетевой ошибкой
     */
    private fun isNetworkError(e: Exception, message: String): Boolean {
        // Проверка по типу исключения
        if (e is UnknownHostException || e is ConnectException || e is SocketTimeoutException ||
            e is NoRouteToHostException || e is SocketException || e is SSLException) {
            return true
        }

        // Проверка по содержимому сообщения
        return NETWORK_ERRORS.any { errorKeyword ->
            message.contains(errorKeyword, ignoreCase = true)
        }
    }

    /**
     * Умное логирование с адаптивной частотой в зависимости от типа ошибки
     */
    private fun logSmartError(errorType: String, context: String, errorClass: String, message: String) {
        val currentTime = System.currentTimeMillis()
        val errorKey = "${errorType}_${context}"
        val lastTime = lastLogTime.getOrDefault(errorKey, 0L)

        // Адаптивный интервал логирования в зависимости от типа ошибки
        val cooldownTime = when (errorType) {
            "DNS" -> 120_000L // DNS ошибки логируем реже - раз в 2 минуты
            "TIMEOUT" -> 60_000L // Таймауты - раз в минуту
            "CONNECTION_REFUSED", "CONNECTION_POOL" -> 30_000L // Проблемы соединения - раз в 30 секунд
            else -> ERROR_LOG_COOLDOWN // Остальные - стандартный интервал
        }

        if (currentTime - lastTime >= cooldownTime) {
            lastLogTime[errorKey] = currentTime

            // Краткое, информативное сообщение без stacktrace
            val userFriendlyMessage = getUserFriendlyErrorMessage(errorType, context)
            plugin.logger.warning(userFriendlyMessage)

            // Детальная информация только в режиме отладки
            if (plugin.logger.isLoggable(java.util.logging.Level.FINE)) {
                plugin.logger.fine("Error details - Class: $errorClass, Message: $message")
            }
        }
    }

    /**
     * Генерирует понятные пользователю сообщения об ошибках
     */
    private fun getUserFriendlyErrorMessage(errorType: String, context: String): String {
        return when (errorType) {
            "DNS" -> "Could not resolve api.telegram.org (DNS issues)"
            "CONNECTION_REFUSED" -> "Telegram API refused connection"
            "TIMEOUT" -> "Connection timeout to Telegram API"
            "ROUTING" -> "No route to Telegram servers"
            "SSL" -> "SSL connection error with Telegram API"
            "SOCKET" -> "Network socket error"
            "CONNECTION_POOL" -> "Connection pool closed or unavailable"
            else -> "Network error with Telegram API ($context)"
        }
    }

    /**
     * Проверяет, активно ли соединение
     */
    fun isConnectionActive(): Boolean = connectionState.get()

    /**
     * Получает статистику ошибок
     */
    fun getErrorStats(): Pair<Long, Long> = Pair(errorCount.get(), lastErrorTime.get())

    override fun getBotToken(): String {
        return TConf.botToken
    }

    fun start() {
        try {
            // Останавливаем мониторинг перед перезапуском
            connectionMonitor.stopMonitoring()

            // Предотвращаем повторный запуск
            @Suppress("SENSELESS_COMPARISON")
            if (botsApi != null || botSession != null) {
                logThrottled("BOT_START", "Detected attempt to start already running bot, stopping existing instance first", "WARNING")
                stop() // Останавливаем существующий бот перед запуском нового
                Thread.sleep(2000) // Даем время для полной остановки
            }

            // Сбрасываем состояние ошибок и счетчики переподключений
            connectionState.set(false)
            errorCount.set(0)
            lastErrorTime.set(0)
            reconnectionManager.resetCounters()

            // Безопасная очистка существующих потоков
            cleanupExistingThreads()

            // Принудительно вызываем сборщик мусора для освобождения ресурсов
            try {
                System.gc()
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }

            // Устанавливаем таймауты HTTP клиента
            setupHttpTimeouts()

            // Создаем экземпляр API с защищенной обработкой ошибок
            botsApi = TelegramBotsApi(DefaultBotSession::class.java)

            // Очищаем предыдущие сессии
            try {
                val clean = DeleteWebhook()
                clean.dropPendingUpdates = true
                execute(clean)
                Thread.sleep(500)
            } catch (e: Exception) {
                handleConnectionError(e, "WEBHOOK_CLEANUP")
            }

            // Регистрируем бота с получением ссылки на сессию
            val session = botsApi!!.registerBot(this)

            // Сохраняем ссылку на сессию для корректного управления
            if (session is DefaultBotSession) {
                botSession = session
                connectionState.set(true) // Устанавливаем состояние как активное

                // Успешное подключение - сбрасываем счетчики ошибок
                reconnectionManager.resetCounters()
            }

            // Запускаем мониторинг соединения
            connectionMonitor.startMonitoring()

            plugin.logger.info("Telegram bot started successfully! Connection monitoring enabled.")

        } catch (e: TelegramApiException) {
            handleConnectionError(e, "BOT_START")

            // Обнуляем ссылки на случай частичной инициализации
            try {
                botSession = null
                botsApi = null
                connectionState.set(false)
            } catch (ex: Exception) {
                // Игнорируем ошибки очистки
            }

            throw e
        } catch (e: Exception) {
            handleConnectionError(e, "BOT_START_UNEXPECTED")

            // Очистка при неожиданных ошибках
            try {
                botSession = null
                botsApi = null
                connectionState.set(false)
            } catch (ex: Exception) {
                // Игнорируем ошибки очистки
            }

            throw e
        }
    }

    /**
     * Безопасная очистка существующих Telegram потоков
     */
    private fun cleanupExistingThreads() {
        try {
            val threadGroup = Thread.currentThread().threadGroup
            val threads = arrayOfNulls<Thread>(threadGroup.activeCount() * 2)
            threadGroup.enumerate(threads, true)

            var cleanedCount = 0
            val currentThread = Thread.currentThread()

            for (thread in threads) {
                if (thread != null && thread != currentThread && !thread.isInterrupted &&
                   (thread.name.contains("DefaultBotSession") ||
                    thread.name.contains("Telegram") ||
                    thread.name.contains("telegram"))) {
                    try {
                        thread.interrupt()
                        cleanedCount++
                    } catch (e: Exception) {
                        // Игнорируем ошибки прерывания потоков
                    }
                }
            }

            if (cleanedCount > 0) {
                logThrottled("THREAD_CLEANUP", "Cleaned up $cleanedCount existing Telegram threads before start")
                Thread.sleep(1000) // Даем время для завершения потоков
            }

        } catch (e: Exception) {
            logThrottled("THREAD_CLEANUP", "Error during thread cleanup: ${e.message}", "WARNING")
        }
    }

    // Метод для настройки таймаутов HTTP клиента
    private fun setupHttpTimeouts() {
        try {
            // Настраиваем системные свойства для Apache HTTP Client
            System.setProperty("http.keepAlive", "false")
            System.setProperty("http.maxConnections", "10")
            System.setProperty("sun.net.http.errorstream.enableBuffering", "true")
            System.setProperty("sun.net.client.defaultConnectTimeout", "10000")
            System.setProperty("sun.net.client.defaultReadTimeout", "10000")

            // Для тонкой настройки Apache HTTP Client нужно было бы использовать
            // отдельный экземпляр HttpClient с настроенными параметрами
        } catch (e: Exception) {
            plugin.logger.warning("Error setting up HTTP timeouts: ${e.message}")
        }
    }

    fun stop() {
        // Останавливаем мониторинг соединения и менеджер переподключений
        connectionMonitor.stopMonitoring()
        reconnectionManager.shutdown()

        // Предотвращаем повторную остановку
        @Suppress("SENSELESS_COMPARISON")
        if (botsApi == null && botSession == null) {
            logThrottled("BOT_STOP", "Bot is already stopped or was never started")
            return
        }

        // Устанавливаем состояние как неактивное
        connectionState.set(false)

        // Создаем отдельный поток для безопасной остановки бота
        val shutdownThread = Thread {
            try {
                // Очищаем ссылку на API для предотвращения новых запросов
                botsApi = null

                // Работаем с локальной копией botSession для безопасности
                val localBotSession = botSession
                botSession = null

                // Пытаемся очистить обновления с обработкой ошибок
                try {
                    val clean = DeleteWebhook()
                    clean.dropPendingUpdates = true
                    execute(clean)
                    Thread.sleep(500)
                } catch (e: Exception) {
                    logThrottled("WEBHOOK_CLEANUP", "Error cleaning webhook: ${e.javaClass.simpleName}", "WARNING")
                }

                // Закрываем сессию с защищенной обработкой
                if (localBotSession != null) {
                    try {
                        localBotSession.stop()
                        Thread.sleep(500)
                        logThrottled("BOT_STOP", "Bot session stopped successfully")
                    } catch (e: Exception) {
                        logThrottled("SESSION_STOP", "Error stopping session: ${e.javaClass.simpleName}", "WARNING")
                    }
                }

                // Безопасная очистка потоков
                cleanupTelegramThreads()

                plugin.logger.info("Telegram bot stopped successfully")

            } catch (e: Exception) {
                plugin.logger.warning("Error stopping bot: ${e.javaClass.simpleName}")
            } finally {
                // Финальная очистка состояния
                try {
                    botSession = null
                    botsApi = null
                    connectionState.set(false)

                    // Принудительная сборка мусора для освобождения ресурсов
                    System.gc()
                } catch (e: Exception) {
                    // Игнорируем ошибки финальной очистки
                }
            }
        }

        // Настраиваем поток остановки
        shutdownThread.isDaemon = true
        shutdownThread.name = "ZTelegram-Safe-Shutdown-Thread"

        // Запускаем и ждем завершения с таймаутом
        shutdownThread.start()
        try {
            shutdownThread.join(7000) // Увеличиваем время ожидания до 7 секунд
        } catch (e: InterruptedException) {
            logThrottled("BOT_STOP", "Interrupted while waiting for bot shutdown", "WARNING")
            Thread.currentThread().interrupt()
        }

        // Проверяем, завершился ли поток остановки
        if (shutdownThread.isAlive) {
            logThrottled("BOT_STOP", "Bot shutdown taking longer than expected, forcing termination", "WARNING")
            try {
                shutdownThread.interrupt()
            } catch (e: Exception) {
                // Игнорируем ошибки принудительного прерывания
            }
        }
    }

    /**
     * Безопасная очистка Telegram потоков при остановке
     */
    private fun cleanupTelegramThreads() {
        try {
            val threadGroup = Thread.currentThread().threadGroup
            val threads = arrayOfNulls<Thread>(threadGroup.activeCount() * 2)
            threadGroup.enumerate(threads, true)

            var interruptedCount = 0
            val currentThread = Thread.currentThread()

            for (thread in threads) {
                if (thread != null && thread != currentThread && !thread.isInterrupted &&
                   (thread.name.contains("DefaultBotSession") ||
                    thread.name.contains("Telegram") ||
                    thread.name.contains("telegram"))) {
                    try {
                        thread.interrupt()
                        interruptedCount++
                    } catch (e: Exception) {
                        logThrottled("THREAD_CLEANUP", "Error interrupting thread ${thread.name}: ${e.message}", "WARNING")
                    }
                }
            }

            if (interruptedCount > 0) {
                logThrottled("THREAD_CLEANUP", "Interrupted $interruptedCount Telegram threads during shutdown")
                Thread.sleep(1000) // Даем время для завершения потоков
            }

        } catch (e: Exception) {
            logThrottled("THREAD_CLEANUP", "Error during thread cleanup: ${e.message}", "WARNING")
        }
    }

    override fun getBotUsername(): String {
        return "YourTelegramBot"
    }

    /**
     * Проверяет соответствие chatId конфигурированному каналу
     * Поддерживает как обычные каналы (-1001706591095), так и темы (-1001706591095_378632)
     */
    private fun isChannelMatch(actualChatId: String, configuredChannelId: String): Boolean {
        // Если канал не настроен (пустая строка), возвращаем false
        if (configuredChannelId.isEmpty()) {
            if (conf.debugEnabled) {
                plugin.logger.info("❌ NO MATCH: configuredChannelId is empty")
            }
            return false
        }

        // Добавляем отладочную информацию
        if (conf.debugEnabled) {
            plugin.logger.info("Checking channel match: actualChatId='$actualChatId', configuredChannelId='$configuredChannelId'")
        }

        // Точное совпадение (приоритет)
        if (actualChatId == configuredChannelId) {
            if (conf.debugEnabled) {
                plugin.logger.info("✅ EXACT MATCH: '$actualChatId' == '$configuredChannelId'")
            }
            return true
        }

        // Получаем базовые ID каналов (до символа "_")
        val actualBaseId = if (actualChatId.contains("_")) actualChatId.substringBefore("_") else actualChatId

        // Если конфигурированный канал НЕ содержит тему (только базовый ID),
        // то сопоставляем с базовым ID входящего сообщения
        if (!configuredChannelId.contains("_") && actualBaseId == configuredChannelId) {
            if (conf.debugEnabled) {
                plugin.logger.info("✅ BASE MATCH: actualBase='$actualBaseId' == configured='$configuredChannelId' (no topic in config)")
            }
            return true
        }

        if (conf.debugEnabled) {
            plugin.logger.info("❌ NO MATCH: actualChatId='$actualChatId', configuredChannelId='$configuredChannelId'")
        }
        return false
    }

    /**
     * Разбирает chatId на базовый ID чата и ID темы
     * Возвращает Pair(baseChatId, threadId)
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

    /**
     * Получает канал для игр
     * Если game канал не настроен - использует основной канал
     */
    private fun getGameChannelId(): String {
        return if (conf.gameChannelId.isNotEmpty()) {
            conf.gameChannelId
        } else {
            conf.mainChannelId
        }
    }

    /**
     * Получает канал для статистики
     * Если statistics канал не настроен - использует основной канал
     */
    private fun getStatisticsChannelId(): String {
        return if (conf.statisticsChannelId.isNotEmpty()) {
            conf.statisticsChannelId
        } else {
            conf.mainChannelId
        }
    }

    /**
     * Определяет тип канала по его ID
     * ВАЖНО: Если все каналы имеют один и тот же ID, приоритет отдается основному каналу
     */
    private fun getChannelType(chatId: String): String {
        // Сначала проверяем точные совпадения с темами
        // ВАЖНО: Порядок проверки имеет значение! Если каналы одинаковые, проверяем в порядке приоритета
        
        // Проверяем, все ли каналы одинаковые (для случая единого канала без тем)
        val allChannelsSame = conf.mainChannelId.isNotEmpty() && 
                              conf.mainChannelId == conf.registerChannelId && 
                              conf.mainChannelId == conf.consoleChannelId && 
                              conf.mainChannelId == conf.gameChannelId && 
                              conf.mainChannelId == conf.statisticsChannelId
        
        if (allChannelsSame) {
            // Если все каналы одинаковые, используем контекст обработки сообщения
            // Определяем тип канала по тому, какой обработчик был вызван
            // По умолчанию возвращаем "main", так как большинство команд работают в основном канале
            val actualBaseId = if (chatId.contains("_")) chatId.substringBefore("_") else chatId
            val mainBaseId = if (conf.mainChannelId.contains("_")) conf.mainChannelId.substringBefore("_") else conf.mainChannelId
            
            if (actualBaseId == mainBaseId) {
                // Если это точно основной канал (или все каналы одинаковые), возвращаем "main"
                // Это позволит всем командам работать в едином канале
                return "main"
            }
        }
        
        // Обычная логика для разных каналов
        if (conf.registerChannelId.isNotEmpty() && chatId == conf.registerChannelId) return "register"
        if (conf.gameChannelId.isNotEmpty() && chatId == conf.gameChannelId) return "game"
        if (conf.statisticsChannelId.isNotEmpty() && chatId == conf.statisticsChannelId) return "statistics"
        if (conf.consoleChannelId.isNotEmpty() && chatId == conf.consoleChannelId) return "console"
        if (conf.mainChannelId.isNotEmpty() && chatId == conf.mainChannelId) return "main"

        // Затем проверяем базовые совпадения каналов (для случаев без темы)
        val actualBaseId = if (chatId.contains("_")) chatId.substringBefore("_") else chatId

        // Проверяем базовые ID только если каналы настроены
        if (conf.mainChannelId.isNotEmpty()) {
            val mainBaseId = if (conf.mainChannelId.contains("_")) conf.mainChannelId.substringBefore("_") else conf.mainChannelId
            if (actualBaseId == mainBaseId) return "main"
        }

        if (conf.consoleChannelId.isNotEmpty()) {
            val consoleBaseId = if (conf.consoleChannelId.contains("_")) conf.consoleChannelId.substringBefore("_") else conf.consoleChannelId
            if (actualBaseId == consoleBaseId) return "console"
        }

        return "main" // По умолчанию основной канал
    }

    /**
     * Проверяет, разрешена ли команда в данном канале
     */
    private fun isCommandAllowedInChannel(command: String, channelType: String): Boolean {
        return when (channelType) {
            "main" -> command in listOf("online", "tps", "restart", "cancelrestart", "stats", "top", "topbal", "player", "gender", "rep", "reptop", "reprecent", "random", "menu", "help", "помощь", "pay", "перевод", "платеж")
            "register" -> command in listOf("unreg", "отменить", "list", "список", "help", "помощь")
            "game" -> command in listOf("game", "игра", "help", "помощь")
            "statistics" -> command in listOf("admin", "stats", "статистика", "top", "топ", "topbal", "топбал", "help", "помощь")
            "console" -> true // В консольном канале все команды разрешены
            else -> true
        }
    }

    /**
     * Получает правильный chatId для отправки сообщений
     *
     * ВАЖНО: Эта функция ВСЕГДА возвращает configuredChannelId из конфига без изменений.
     * Если configuredChannelId не содержит "_" (топик не указан), то сообщение
     * будет отправлено в основной канал, а не в топик откуда пришло сообщение.
     *
     * Это предотвращает ошибку "message thread not found" когда пользователь
     * пишет из топика, но канал в конфиге настроен без топика.
     */
    private fun getTargetChatId(configuredChannelId: String): String {
        // КРИТИЧЕСКИ ВАЖНО: Всегда используем ТОЛЬКО настроенный канал из конфига!
        // НЕ используем currentChatIdContext или любой другой контекст!
        // Это гарантирует, что сообщения отправляются туда, куда настроено в конфиге.
        if (conf.debugEnabled) {
            plugin.logger.info("[getTargetChatId] Returning configured channel: '$configuredChannelId' (contains topic: ${configuredChannelId.contains("_")})")
        }
        return configuredChannelId
    }

    override fun onUpdateReceived(update: Update) {
        // Проверяем состояние соединения перед обработкой обновлений
        if (!connectionState.get()) {
            logThrottled("UPDATE_RECEIVED", "Skipping update processing - connection is inactive")
            return
        }

        try {
            // Обрабатываем callback_query (нажатия на inline-кнопки)
            if (update.hasCallbackQuery()) {
                val callbackQuery = update.callbackQuery
                // Проверяем, что menuManager инициализирован
                try {
                    if (ZTele.menuManager.handleCallback(callbackQuery)) {
                        return // Callback обработан
                    }
                } catch (e: kotlin.UninitializedPropertyAccessException) {
                    // menuManager еще не инициализирован
                    if (conf.debugEnabled) {
                        plugin.logger.warning("Menu manager not initialized yet, ignoring callback")
                    }
                }
            }
            
            if (!update.hasMessage()) return

            val message = update.message
            if (!message.hasText()) return

            val chatId = message.chatId.toString()
            val text = message.text
            val username = message.from.userName ?: message.from.firstName
            val userId = message.from.id

            // Проверяем, является ли это ответом на сообщение (для репутации)
            val replyToMessage = if (message.isReply) message.replyToMessage else null

            // Фильтрация сообщений
            if (!shouldProcessMessage(message)) {
                if (conf.debugEnabled) {
                    plugin.logger.info("🚫 [MessageFilter] Сообщение от $username ($userId) заблокировано фильтром")
                }
                return
            }

            // Подробное логирование для отладки тем
            if (conf.debugEnabled) {
                plugin.logger.info("=== TELEGRAM MESSAGE DEBUG ===")
                plugin.logger.info("Received message from user: $username, userId: $userId, chatId: $chatId")
                plugin.logger.info("Message text: '$text'")
                plugin.logger.info("Chat type: ${message.chat.type}")
                plugin.logger.info("Chat title: ${message.chat.title}")
                plugin.logger.info("Message thread ID: ${message.messageThreadId}")
                plugin.logger.info("Configured channels - main: '${conf.mainChannelId}', console: '${conf.consoleChannelId}', register: '${conf.registerChannelId}'")
                plugin.logger.info("=== END DEBUG ===")
            }

            // Если это сообщение из темы, формируем правильный chatId
            val actualChatId = if (message.messageThreadId != null && message.messageThreadId != 0) {
                val topicChatId = "${chatId}_${message.messageThreadId}"
                if (conf.debugEnabled) {
                    plugin.logger.info("Message from topic detected! Original: $chatId, Topic ID: ${message.messageThreadId}, Combined: $topicChatId")
                }
                topicChatId
            } else {
                if (conf.debugEnabled) {
                    plugin.logger.info("Message from main channel (no topic)")
                }
                chatId
            }

            if (conf.debugEnabled) {
                plugin.logger.info("Final chatId for processing: $actualChatId")
            }

            // Сохраняем текущий actualChatId в контексте для ответов в ту же тему
            currentChatIdContext.set(actualChatId)

            try {
                // Обработка сообщений с поддержкой тем Telegram
                // ВАЖНО: Порядок проверок имеет значение! Сначала проверяем точные совпадения с темами

                // Сначала проверяем точные совпадения (темы)
                when {
                    // Точное совпадение с каналом регистрации (приоритет)
                    conf.registerChannelId.isNotEmpty() && actualChatId == conf.registerChannelId -> {
                        if (conf.debugEnabled) {
                            plugin.logger.info("🎯 Routing to REGISTER channel handler (EXACT match)")
                        }
                        handleRegisterChannelMessage(message, message.from)
                    }
                    // Точное совпадение с игровым каналом
                    conf.gameChannelId.isNotEmpty() && actualChatId == conf.gameChannelId -> {
                        if (conf.debugEnabled) {
                            plugin.logger.info("🎯 Routing to GAME channel handler (EXACT match)")
                        }
                        handleMainChannelMessage(text, username, userId, replyToMessage) // Игры обрабатываются как команды основного канала
                    }
                    // Точное совпадение с консольным каналом
                    conf.consoleChannelId.isNotEmpty() && actualChatId == conf.consoleChannelId -> {
                        if (conf.debugEnabled) {
                            plugin.logger.info("🎯 Routing to CONSOLE channel handler (EXACT match)")
                        }
                        // Если это команда help, обрабатываем через основную систему команд
                        if (text == "/help" || text == "/помощь") {
                            handleMainChannelCommand(text, username, userId)
                        } else {
                        handleConsoleChannelMessage(text, username)
                        }
                    }
                    // Точное совпадение с основным каналом
                    conf.mainChannelId.isNotEmpty() && actualChatId == conf.mainChannelId -> {
                        if (conf.debugEnabled) {
                            plugin.logger.info("🎯 Routing to MAIN channel handler (EXACT match)")
                        }
                        handleMainChannelMessage(text, username, userId, replyToMessage)
                    }
                    // Базовые совпадения (только если нет точных)
                    conf.gameChannelId.isNotEmpty() && isChannelMatch(actualChatId, conf.gameChannelId) -> {
                        if (conf.debugEnabled) {
                            plugin.logger.info("🎯 Routing to GAME channel handler (BASE match)")
                        }
                        handleMainChannelMessage(text, username, userId, replyToMessage) // Игры обрабатываются как команды основного канала
                    }
                    conf.consoleChannelId.isNotEmpty() && isChannelMatch(actualChatId, conf.consoleChannelId) -> {
                        if (conf.debugEnabled) {
                            plugin.logger.info("🎯 Routing to CONSOLE channel handler (BASE match)")
                        }
                        // Если это команда help, обрабатываем через основную систему команд
                        if (text == "/help" || text == "/помощь") {
                            handleMainChannelCommand(text, username, userId)
                        } else {
                        handleConsoleChannelMessage(text, username)
                        }
                    }
                    conf.mainChannelId.isNotEmpty() && isChannelMatch(actualChatId, conf.mainChannelId) -> {
                        if (conf.debugEnabled) {
                            plugin.logger.info("🎯 Routing to MAIN channel handler (BASE match)")
                        }
                        handleMainChannelMessage(text, username, userId, replyToMessage)
                    }
                    else -> {
                        // Проверяем, совпадает ли базовый ID с основным каналом
                        val actualBaseId = if (actualChatId.contains("_")) actualChatId.substringBefore("_") else actualChatId
                        val mainBaseId = if (conf.mainChannelId.contains("_")) conf.mainChannelId.substringBefore("_") else conf.mainChannelId

                        if (actualBaseId == mainBaseId) {
                            // Сообщение из другой темы того же канала - обрабатываем как основной канал
                            if (conf.debugEnabled) {
                                plugin.logger.info("🎯 Routing to MAIN channel handler (same base channel, different topic)")
                            }
                            handleMainChannelMessage(text, username, userId, replyToMessage)
                        } else if (conf.debugEnabled) {
                            plugin.logger.info("❓ No handler found for chatId: $actualChatId")
                            plugin.logger.info("   Main: '${conf.mainChannelId}'")
                            plugin.logger.info("   Console: '${conf.consoleChannelId}'")
                            plugin.logger.info("   Register: '${conf.registerChannelId}'")
                        }
                    }
                }
            } finally {
                // Очищаем контекст после обработки
                currentChatIdContext.remove()
            }
        } catch (e: Exception) {
            val isCritical = handleConnectionError(e, "UPDATE_PROCESSING")

            if (isCritical) {
                // Критические ошибки уже обрабатываются в handleConnectionError
                // Переподключение планируется автоматически
            }
        }
    }

    private fun handleMainChannelMessage(text: String, username: String, userId: Long, replyToMessage: Message? = null) {
        if (!conf.mainChannelEnabled) return

        // Проверка черного списка
        if (conf.blacklistEnabled && mgr.isPlayerBlacklisted(userId.toString())) {
            // Отправляем сообщение о блокировке в основной канал вместо личного сообщения
            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.blockedMessage, conf.commandsAutoDeleteSeconds)
            return
        }

        // Проверка белого списка
        if (conf.whitelistEnabled && !mgr.isPlayerWhitelisted(userId.toString())) {
            // Отправляем сообщение о необходимости регистрации в основной канал вместо личного сообщения
            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.noRegistrationMessage, conf.commandsAutoDeleteSeconds)
            return
        }

        if (text.startsWith("/")) {
            handleMainChannelCommand(text, username, userId)
            return
        }

        // Проверяем команды репутации (+rep причина, -rep причина, + причина, - причина)
        // Поддерживаем как полные команды (+rep/-rep), так и короткие (+/-)
        val repPattern = Regex("^([+\\-])(?:rep)?(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)
        val repMatch = repPattern.matchEntire(text.trim())

        if (conf.debugEnabled) {
            plugin.logger.info("[REP DEBUG] Text: '$text', Pattern match: ${repMatch != null}, Reply: ${replyToMessage != null}")
            if (repMatch != null) {
                plugin.logger.info("[REP DEBUG] Match groups: ${repMatch.groupValues}")
            }
        }

        if (repMatch != null && replyToMessage != null) {
            val isPositive = repMatch.groupValues[1] == "+"
            val reason = repMatch.groupValues.getOrNull(2)?.trim()?.ifEmpty { null }

            if (conf.debugEnabled) {
                plugin.logger.info("[REP DEBUG] isPositive: $isPositive, reason: '$reason'")
            }

            // Получаем имя источника (кто ставит репутацию)
            val sourceName = mgr.getPlayerByTelegramId(userId.toString()) ?: username

            if (conf.debugEnabled) {
                plugin.logger.info("[REP DEBUG] sourceName: $sourceName")
            }

            // Получаем информацию о целевом пользователе
            val targetUserId = replyToMessage.from.id
            val targetUsername = replyToMessage.from.userName ?: replyToMessage.from.firstName
            val targetPlayerName = mgr.getPlayerByTelegramId(targetUserId.toString())

            if (conf.debugEnabled) {
                plugin.logger.info("[REP DEBUG] targetUserId: $targetUserId, targetUsername: $targetUsername, targetPlayerName: $targetPlayerName")
            }

            if (targetPlayerName == null) {
                sendAutoDeleteMessage(
                    getTargetChatId(conf.mainChannelId),
                    "❌ Пользователь $targetUsername не зарегистрирован в игре!",
                    conf.reputationAutoDeleteSeconds
                )
                return
            }

            // Проверяем права администратора
            val isAdmin = conf.isAdministrator(userId)

            if (conf.debugEnabled) {
                plugin.logger.info("[REP DEBUG] isAdmin: $isAdmin, calling addReputation...")
            }

            // Даем репутацию
            val result = if (isPositive) {
                ZTele.reputation.addPositiveReputation(sourceName, targetPlayerName, reason, isAdmin)
            } else {
                ZTele.reputation.addNegativeReputation(sourceName, targetPlayerName, reason, isAdmin)
            }

            if (conf.debugEnabled) {
                plugin.logger.info("[REP DEBUG] Result type: ${result::class.simpleName}")
            }

            // Обрабатываем результат
            when (result) {
                is org.zoobastiks.ztelegram.reputation.ReputationResult.SuccessWithData -> {
                    // Используем конфигурационные сообщения
                    val messageTemplate = if (result.isPositive) {
                        conf.reputationSuccessPositive
                    } else {
                        conf.reputationSuccessNegative
                    }

                    if (conf.debugEnabled) {
                        plugin.logger.info("[REP DEBUG] messageTemplate length: ${messageTemplate.length}, isEmpty: ${messageTemplate.isEmpty()}")
                        plugin.logger.info("[REP DEBUG] messageTemplate preview: ${messageTemplate.take(100)}")
                    }

                    // КРИТИЧЕСКАЯ ПРОВЕРКА: если шаблон пустой, используем fallback
                    if (messageTemplate.isEmpty()) {
                        plugin.logger.severe("❌ [REP CRITICAL] messageTemplate пустой! Конфиг не загружен корректно!")
                        plugin.logger.severe("   [REP CRITICAL] Используем fallback сообщение")

                        val fallbackMessage = if (result.isPositive) {
                            "👍 **$sourceName** повысил репутацию игрока **$targetPlayerName**\n⭐ Рейтинг: +**${result.targetData.totalReputation}** (${result.targetData.reputationLevel.emoji} ${result.targetData.reputationLevel.displayName})"
                        } else {
                            "👎 **$sourceName** понизил репутацию игрока **$targetPlayerName**\n⭐ Рейтинг: **${result.targetData.totalReputation}** (${result.targetData.reputationLevel.emoji} ${result.targetData.reputationLevel.displayName})"
                        }

                        sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), fallbackMessage, conf.reputationAutoDeleteSeconds)
                        return
                    }

                    // Формируем строку причины
                    val reasonLine = if (result.reason != null) {
                        conf.reputationReasonLine.replace("%reason%", result.reason)
                    } else {
                        ""
                    }

                    // Заменяем плейсхолдеры
                    val message = messageTemplate
                        .replace("%source%", sourceName)
                        .replace("%target%", targetPlayerName)
                        .replace("%reason_line%", reasonLine)
                        .replace("%total%", result.targetData.totalReputation.toString())
                        .replace("%level%", "${result.targetData.reputationLevel.emoji} ${result.targetData.reputationLevel.displayName}")
                        .replace("%positive%", result.targetData.positiveRep.toString())
                        .replace("%negative%", result.targetData.negativeRep.toString())

                    if (conf.debugEnabled) {
                        plugin.logger.info("[REP DEBUG] Final message length: ${message.length}, isEmpty: ${message.isEmpty()}")
                        plugin.logger.info("[REP DEBUG] Calling sendAutoDeleteMessage...")
                    }

                    // Дополнительная проверка перед отправкой
                    if (message.isEmpty()) {
                        plugin.logger.severe("❌ [REP CRITICAL] Финальное сообщение пустое после замены плейсхолдеров!")
                        return
                    }

                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.reputationAutoDeleteSeconds)
                }

                is org.zoobastiks.ztelegram.reputation.ReputationResult.Failure -> {
                    sendAutoDeleteMessage(
                        getTargetChatId(conf.mainChannelId),
                        "❌ ${result.message}",
                        conf.reputationAutoDeleteSeconds
                    )
                }

                is org.zoobastiks.ztelegram.reputation.ReputationResult.Cooldown -> {
                    val hours = result.remainingMinutes / 60
                    val minutes = result.remainingMinutes % 60
                    val timeStr = if (hours > 0) {
                        "${hours}ч ${minutes}м"
                    } else {
                        "${minutes}м"
                    }

                    val message = conf.reputationCooldown
                        .replace("%target%", targetPlayerName)
                        .replace("%time%", timeStr)

                    sendAutoDeleteMessage(
                        getTargetChatId(conf.mainChannelId),
                        message,
                        conf.reputationAutoDeleteSeconds
                    )
                }

                else -> {}
            }
            return
        }

        // Проверяем, не играет ли пользователь в игру
        if (ZTele.game.hasActiveGame(userId.toString())) {
            // Обрабатываем ответ на игру
            val (_, message) = ZTele.game.checkAnswer(userId.toString(), text)

            // Отправляем ответ через автоудаляемое сообщение, независимо от правильности ответа
            sendAutoDeleteMessage(getTargetChatId(getGameChannelId()), message, conf.gameAutoDeleteSeconds)

            // Не отправляем обычное сообщение в чат
            return
        }

        // Рендеринг [item], [inv], [ender]
        if (text.equals("[item]", true) || text.equals("[inv]", true) || text.equals("[ender]", true)) {
            // Сначала пытаемся получить зарегистрированного игрока
            var playerName = mgr.getPlayerByTelegramId(userId.toString())
            var player: org.bukkit.entity.Player? = null
            
            // Если не зарегистрирован, пробуем найти игрока по имени из Telegram
            if (playerName == null) {
                // Используем username из Telegram как никнейм
                playerName = username
                player = Bukkit.getPlayerExact(playerName)
                
                if (player == null) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), 
                        "❌ Игрок с ником \"$playerName\" не найден на сервере!\n💡 Зарегистрируйте аккаунт или зайдите на сервер под этим ником", 
                        conf.commandsAutoDeleteSeconds)
                    return
                }
            } else {
                player = Bukkit.getPlayerExact(playerName)
                if (player == null) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), 
                        "❌ Игрок $playerName не в сети!", 
                        conf.commandsAutoDeleteSeconds)
                    return
                }
            }
            
            // Если дошли сюда - player не null
            when {
                text.equals("[item]", true) -> {
                    val item = player!!.inventory.itemInMainHand
                    if (item.type != Material.AIR) {
                        try {
                            val renderer = ItemRenderer()
                            val imageBytes = renderer.renderItemToFile(item).first
                            val itemName = item.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
                            val caption = "$playerName: [$itemName]"
                            val currentChatId = currentChatIdContext.get() ?: conf.mainChannelId
                            sendPhoto(currentChatId, imageBytes, caption)
                        } catch (e: Exception) {
                            plugin.logger.warning("Failed to render item: ${e.message}")
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), 
                                "❌ Не удалось отрендерить предмет", 
                                conf.commandsAutoDeleteSeconds)
                        }
                    } else {
                        sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), 
                            "❌ У вас нет предмета в руке!", 
                            conf.commandsAutoDeleteSeconds)
                    }
                }
                
                text.equals("[inv]", true) -> {
                    try {
                        val renderer = InventoryRenderer()
                        val imageBytes = renderer.renderInventory(player!!.inventory)
                        val caption = "$playerName: Инвентарь"
                        val currentChatId = currentChatIdContext.get() ?: conf.mainChannelId
                        sendPhoto(currentChatId, imageBytes, caption)
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed to render inventory: ${e.message}")
                        sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), 
                            "❌ Не удалось отрендерить инвентарь", 
                            conf.commandsAutoDeleteSeconds)
                    }
                }
                
                text.equals("[ender]", true) -> {
                    try {
                        val renderer = EnderChestRenderer()
                        val imageBytes = renderer.renderEnderChest(player!!.enderChest)
                        val caption = "$playerName: Эндер-сундук"
                        val currentChatId = currentChatIdContext.get() ?: conf.mainChannelId
                        sendPhoto(currentChatId, imageBytes, caption)
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed to render ender chest: ${e.message}")
                        sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), 
                            "❌ Не удалось отрендерить эндер-сундук", 
                            conf.commandsAutoDeleteSeconds)
                    }
                }
            }
            return
        }

        if (conf.mainChannelChatEnabled && conf.chatTelegramToMinecraftEnabled) {
            // Получаем связанный игровой ник, если пользователь зарегистрирован
            val playerName = mgr.getPlayerByTelegramId(userId.toString()) ?: username
            
            // Определяем, в какой чат отправлять (по chatId откуда пришло сообщение)
            val currentChatId = currentChatIdContext.get() ?: conf.mainChannelId
            val (baseChatId, topicId) = parseChatId(currentChatId)
            
            // Ищем чат в game_chats по chatId и topicId
            var targetChat = ZTele.chatManager.getChats().find { 
                it.chatId.toString() == baseChatId && it.topicId == (topicId ?: 0)
            }
            
            if (targetChat == null && conf.gameChatsEnabled) {
                // Если не нашли - берем дефолтный
                targetChat = ZTele.chatManager.getDefaultChat()
            }
            
            if (targetChat != null && targetChat.enabled) {
                // Отправляем в правильный игровой чат через ChatManager
                ZTele.chatManager.sendToGame(targetChat, playerName, text)
            } else {
                // Fallback на старую систему
                val context = PlaceholderEngine.createCustomContext(mapOf(
                    "username" to playerName,
                    "message" to text
                ))
                val formattedMessage = PlaceholderEngine.process(conf.chatTelegramToMinecraftFormat, context)
                    .replace("\\n", "\n")
                sendFormattedMessageToServer(formattedMessage)
            }
        }
    }

    // Универсальный метод для отправки отформатированных сообщений на сервер
    private fun sendFormattedMessageToServer(message: String) {
        // Проверяем наличие MiniMessage форматирования
        if (message.contains("<") && message.contains(">")) {
            // Если есть MiniMessage теги (градиенты и др.)
            val component = GradientUtils.parseMixedFormat(message)
            Bukkit.getServer().sendMessage(component)
        } else {
            // Для обычных цветовых кодов
            val processedMessage = ColorUtils.translateColorCodes(message)
            Bukkit.getServer().broadcast(Component.text().append(
                LegacyComponentSerializer.legacySection().deserialize(processedMessage)
            ).build())
        }
    }

    // УДАЛЕН: Старый метод formatMessage заменен на PlaceholderEngine

    /**
     * Быстрая замена плейсхолдеров с использованием PlaceholderEngine
     */
    private fun processPlaceholders(template: String, placeholders: Map<String, String>): String {
        val context = PlaceholderEngine.createCustomContext(placeholders)
        return PlaceholderEngine.process(template, context)
    }

    /**
     * Получает топ игроков по балансу
     */
    private fun getTopBalances(limit: Int): List<Pair<String, Double>> {
        val onlinePlayers = Bukkit.getOnlinePlayers()
        val balances = mutableListOf<Pair<String, Double>>()

        for (player in onlinePlayers) {
            try {
                // Попытка получить баланс через Vault API
                val balance = getPlayerBalance(player.name)
                if (balance > 0) {
                    balances.add(Pair(player.name, balance))
                }
            } catch (e: Exception) {
                plugin.logger.warning("Could not get balance for player ${player.name}: ${e.message}")
            }
        }

        // Также проверяем оффлайн игроков, которые были онлайн недавно
        for ((_, logs) in ZTele.stats.playerJoinLogs) {
            val latestLog = logs.lastOrNull()
            if (latestLog != null && !onlinePlayers.any { it.name == latestLog.playerName }) {
                try {
                    val balance = getPlayerBalance(latestLog.playerName)
                    if (balance > 0) {
                        balances.add(Pair(latestLog.playerName, balance))
                    }
                } catch (e: Exception) {
                    // Игнорируем ошибки для оффлайн игроков
                }
            }
        }

        return balances.distinctBy { it.first }
            .sortedByDescending { it.second }
            .take(limit)
    }


    /**
     * Отправляет ежедневную сводку в главный канал
     */
    fun sendDailySummary(stats: org.zoobastiks.ztelegram.stats.StatsManager.StatsResult,
                        playtimeTop: List<org.zoobastiks.ztelegram.stats.StatsManager.PlaytimeEntry>,
                        date: java.time.LocalDate,
                        newPlayersCount: Int = 0) {
        try {
            val dateStr = date.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))

            val topList = if (playtimeTop.isNotEmpty()) {
                playtimeTop.take(5).mapIndexed { index, entry ->
                    val place = index + 1
                    val medal = when (place) {
                        1 -> "🥇"
                        2 -> "🥈"
                        3 -> "🥉"
                        else -> "$place."
                    }
                    val playtime = ZTele.stats.formatPlaytime(entry.minutes)
                    "$medal ${entry.playerName} - $playtime"
                }.joinToString("\n")
            } else {
                "Нет данных"
            }

            val summary = buildString {
                append("📊 **Ежедневная сводка за $dateStr**\n\n")
                append("🆕 **Новые игроки:** $newPlayersCount\n")
                append("🏆 **Топ-5 по времени игры:**\n")
                append(topList)
                append("\n\n")
                append("🎮 **Всего активности:** ${playtimeTop.sumOf { it.minutes }} минут")
            }

            sendToMainChannel(summary)

        } catch (e: Exception) {
            plugin.logger.warning("Failed to send daily summary: ${e.message}")
        }
    }

    private fun handleMainChannelCommand(command: String, username: String, userId: Long) {
        if (conf.debugEnabled) {
            plugin.logger.info("Processing command from user: $username, userId: $userId")
        }

        // Определяем тип канала для контекстных ограничений
        // ВАЖНО: currentChatId используется ТОЛЬКО для определения типа канала (channelType),
        // но НЕ для отправки ответов! Ответы всегда отправляются в conf.mainChannelId.
        val currentChatId = currentChatIdContext.get() ?: conf.mainChannelId
        val channelType = getChannelType(currentChatId)

        // Создаем карту команд и их псевдонимов
        val commandAliases = mapOf(
            "checkin" to setOf("/checkin"),
            "admin" to setOf("/admin", "/админ"),
            "online" to setOf("/online", "/онлайн"),
            "tps" to setOf("/tps", "/тпс"),
            "restart" to setOf("/restart", "/рестарт"),
            "cancelrestart" to setOf("/cancelrestart", "/отменитьрестарт"),
            "gender" to setOf("/gender", "/пол"),
            "player" to setOf("/player", "/ник", "/игрок"),
            "help" to setOf("/help", "/помощь"),
            "unreg" to setOf("/unreg", "/отменить"),
            "list" to setOf("/list", "/список"),
            "game" to setOf("/game", "/игра"),
            "stats" to setOf("/stats", "/статистика"),
            "top" to setOf("/top", "/топ"),
            "topbal" to setOf("/topbal", "/топбал"),
            "rep" to setOf("/rep", "/репутация"),
            "reptop" to setOf("/reptop", "/топрепутация"),
            "reprecent" to setOf("/reprecent", "/репизменения"),
            "random" to setOf("/random", "/рулетка", "/рандом"),
            "menu" to setOf("/menu", "/меню"),
            "pay" to setOf("/pay", "/перевод", "/платеж")
        )

        // Разделяем команду и аргументы
        val parts = command.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val arguments = if (parts.size > 1) parts[1] else ""

        // Определяем, какая команда была вызвана
        for ((key, aliases) in commandAliases) {
            if (aliases.contains(cmd)) {
                // Проверяем, разрешена ли команда в данном канале
                if (!isCommandAllowedInChannel(key, channelType)) {
                    // ИСПРАВЛЕНО: Используем conf.mainChannelId вместо currentChatId
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.errorsCommandNotAllowed, conf.commandsAutoDeleteSeconds)
                    return
                }

                // Проверяем права администратора для команд, которые требуют админ доступ
                if (key in listOf("restart", "cancelrestart", "list") && !conf.isAdministrator(userId)) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.errorsNoAdminPermission, conf.commandsAutoDeleteSeconds)
                    return
                }

                // Выполняем команду
                executeCommand(key, arguments, username, userId, channelType)
                return
            }
        }

        // Если команда не найдена
        sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), "❌ **Неизвестная команда**\nИспользуйте `/help` для списка команд", conf.commandsAutoDeleteSeconds)
    }

    private fun executeCommand(command: String, arguments: String, username: String, userId: Long, channelType: String) {
        when (command) {
            "checkin" -> {
                if (!conf.checkinEnabled) return
                val playerName = mgr.getPlayerByTelegramId(userId.toString())
                if (playerName == null && conf.checkinRequireRegistration) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), 
                        "❌ Вы не зарегистрированы!", conf.commandsAutoDeleteSeconds)
                    return
                }
                val checkinKey = playerName ?: "tg_$userId"
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    val result = ZTele.checkinManager.checkin(checkinKey)
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), 
                            result.message, conf.commandsAutoDeleteSeconds)
                    })
                })
            }
            
            "admin" -> {
                // Проверяем права администратора
                if (conf.debugEnabled) {
                    plugin.logger.info("🔧 [Admin Command] Проверка прав для пользователя ID: $userId")
                    plugin.logger.info("🔧 [Admin Command] Список админов: ${conf.administratorIds}")
                    plugin.logger.info("🔧 [Admin Command] Результат проверки: ${conf.isAdministrator(userId)}")
                }

                if (!conf.isAdministrator(userId)) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.errorsNoAdminPermission, conf.commandsAutoDeleteSeconds)
                    return
                }

                // Обрабатываем подкоманды админа
                val args = arguments.split(" ", limit = 2)
                if (args.isEmpty()) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), "❌ Укажите команду: /admin [top|topbal]", conf.commandsAutoDeleteSeconds)
                    return
                }

                when (args[0].lowercase()) {
                    "top" -> {
                        // Только в канале статистики
                        if (channelType != "statistics") {
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), "❌ Команда доступна только в канале статистики!", conf.commandsAutoDeleteSeconds)
                            return
                        }

                        // Мгновенно запускаем топ по времени игры
                        sendAutoPlaytimeTop()
                    }
                    "topbal" -> {
                        // Только в канале статистики
                        if (channelType != "statistics") {
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), "❌ Команда доступна только в канале статистики!", conf.commandsAutoDeleteSeconds)
                            return
                        }

                        // Мгновенно запускаем топ по балансу
                        sendAutoBalanceTop()
                    }
                    else -> {
                        sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), "❌ Неизвестная команда: /admin ${args[0]}\nДоступны: top, topbal", conf.commandsAutoDeleteSeconds)
                    }
                }
            }

            "online" -> {
                if (!conf.enabledOnlineCommand) return

                // Проверяем, есть ли игроки онлайн
                val onlinePlayers = Bukkit.getOnlinePlayers()
                    .filter { !mgr.isPlayerHidden(it.name) }

                val response = if (onlinePlayers.isEmpty()) {
                    // Если сервер пустой, используем специальное сообщение
                    conf.onlineCommandNoPlayers
                } else {
                    // Используем стандартный ответ с плейсхолдерами
                    PlaceholderEngine.process(conf.onlineCommandResponse)
                }

                sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), response, conf.commandsAutoDeleteSeconds)
            }

            "tps" -> {
                if (!conf.enabledTpsCommand) return

                // Используем новое подробное сообщение с TPS и статусом
                val response = PlaceholderEngine.process(conf.tpsCommandMessage)

                sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), response, conf.commandsAutoDeleteSeconds)
            }

            "restart" -> {
                // Права администратора уже проверены ранее

                val args = if (arguments.isNotEmpty()) listOf(command, arguments) else listOf(command)

                when {
                    args.size == 1 -> {
                        // Мгновенная перезагрузка
                        sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.restartImmediateMessage, conf.commandsAutoDeleteSeconds)

                        // Выполняем команду рестарта в консоли
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), conf.restartImmediateCommand)
                        })
                    }

                    arguments.lowercase() == "cancel" -> {
                        // Отмена таймера рестарта
                        val cancelled = ZTele.restartManager.cancelScheduledRestart(username)

                        if (cancelled) {
                            val message = conf.restartTelegramTimerCancelled.replace("%admin%", username)
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.commandsAutoDeleteSeconds)
                        } else {
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), "❌ Нет активных таймеров рестарта", conf.commandsAutoDeleteSeconds)
                        }
                    }

                    else -> {
                        // Отложенная перезагрузка с таймером
                        val delayMinutes = parseTimeToMinutes(arguments)

                        if (delayMinutes == null) {
                            val usage = "Использование: `/restart [время]`\nПример: `/restart 5m` (через 5 минут)"
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), usage, conf.commandsAutoDeleteSeconds)
                            return
                        }

                        if (delayMinutes < 1 || delayMinutes > 60) {
                            val error = "❌ Время должно быть от 1 до 60 минут"
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), error, conf.commandsAutoDeleteSeconds)
                            return
                        }

                        // Проверяем, есть ли уже активный таймер
                        val activeTask = ZTele.restartManager.getActiveRestartInfo()
                        if (activeTask != null) {
                            val remaining = ZTele.restartManager.getRemainingTime() ?: "неизвестно"
                            val message = conf.restartTelegramTimerActive.replace("%remaining%", remaining)
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.commandsAutoDeleteSeconds)
                            return
                        }

                        // Запланировать рестарт
                        val success = ZTele.restartManager.scheduleRestart(delayMinutes, username)

                        if (success) {
                            val message = conf.restartTelegramTimerStarted
                                .replace("%time%", formatTime(delayMinutes))
                                .replace("%admin%", username)
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.commandsAutoDeleteSeconds)
                        } else {
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), "❌ Ошибка при планировании рестарта", conf.commandsAutoDeleteSeconds)
                        }
                    }
                }
            }

            "cancelrestart" -> {
                // Права администратора уже проверены ранее

                if (ZTele.restartManager.cancelScheduledRestart(username)) {
                    // Создаем контекст для заполнителей
                    val context = PlaceholderEngine.PlaceholderContext().apply {
                        customPlaceholders["admin"] = username
                    }
                    val message = PlaceholderEngine.process(conf.restartTelegramCancelSuccess, context)
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.commandsAutoDeleteSeconds)
                } else {
                    val message = conf.restartTelegramCancelNoRestart
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.commandsAutoDeleteSeconds)
                }
            }

            "gender" -> {
                if (!conf.enabledGenderCommand) return

                if (arguments.isEmpty()) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.genderCommandUsage.replace("\\n", "\n"), conf.commandsAutoDeleteSeconds)
                    return
                }

                // Получаем игрока по Telegram ID
                val player = mgr.getPlayerByTelegramId(userId.toString())

                if (player == null) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.genderCommandNoPlayer.replace("\\n", "\n"), conf.commandsAutoDeleteSeconds)
                    return
                }

                val genderArg = arguments.lowercase()
                val gender = when {
                    genderArg == "man" || genderArg == "м" -> "man"
                    genderArg == "girl" || genderArg == "ж" -> "girl"
                    else -> null
                }

                if (gender == null) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.genderCommandUsage.replace("\\n", "\n"), conf.commandsAutoDeleteSeconds)
                    return
                }

                // Устанавливаем пол игрока
                if (mgr.setPlayerGender(player, gender)) {
                    val context = PlaceholderEngine.createCustomContext(mapOf(
                        "player" to player,
                        "gender" to conf.getGenderTranslation(gender)
                    ))
                    val response = PlaceholderEngine.process(conf.genderCommandResponse, context)
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), response, conf.commandsAutoDeleteSeconds)
                }
            }

            "player" -> {
                if (!conf.enabledPlayerCommand) return

                if (arguments.isEmpty()) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.playerCommandUsage.replace("\\n", "\n"), conf.commandsAutoDeleteSeconds)
                    return
                }

                val playerName = arguments.split(" ")[0]
                val playerData = mgr.getPlayerData(playerName)

                // Проверяем, существует ли игрок в Minecraft, даже если не зарегистрирован
                val isOnline = Bukkit.getPlayerExact(playerName) != null
                val offlinePlayer = Bukkit.getOfflinePlayer(playerName)

                if (!offlinePlayer.hasPlayedBefore() && !isOnline) {
                    val context = PlaceholderEngine.createCustomContext(mapOf("player" to playerName))
                    val response = PlaceholderEngine.process(conf.playerCommandNoPlayer, context)
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), response, conf.commandsAutoDeleteSeconds)
                    return
                }

                val rawGender = playerData?.gender ?: "Not set"
                // Используем перевод для gender
                val gender = if (rawGender == "man" || rawGender == "girl") conf.getGenderTranslation(rawGender) else conf.getStatusTranslation("not_set")

                // Форматируем баланс с двумя знаками после запятой
                val rawBalance = getPlayerBalance(playerName)
                val balance = String.format("%.2f", rawBalance)

                val currentHealth = if (isOnline) Bukkit.getPlayerExact(playerName)?.health?.toInt() ?: 0 else 0
                val coords = if (isOnline) {
                    val loc = Bukkit.getPlayerExact(playerName)?.location
                    "X: ${loc?.blockX}, Y: ${loc?.blockY}, Z: ${loc?.blockZ}"
                } else conf.getStatusTranslation("offline_coords")

                // Переводим статусы для отображения
                val onlineStatus = if (isOnline) conf.getStatusTranslation("online") else conf.getStatusTranslation("offline")

                // Форматируем дату регистрации с корректным форматом
                val registeredDate = if (playerData?.registered != null) {
                    try {
                        // Парсим исходную дату
                        val originalFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        val date = originalFormat.parse(playerData.registered)

                        // Устанавливаем часовой пояс МСК (+3)
                        originalFormat.timeZone = java.util.TimeZone.getTimeZone("Europe/Moscow")

                        // Форматируем дату в нужный формат
                        val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm")
                        dateFormat.timeZone = java.util.TimeZone.getTimeZone("Europe/Moscow")
                        dateFormat.format(date)
                    } catch (e: Exception) {
                        // В случае ошибки парсинга оставляем исходную дату
                        playerData.registered
                    }
                } else conf.getStatusTranslation("not_registered")

                // Добавляем новую информацию
                val firstPlayed = if (offlinePlayer.hasPlayedBefore()) {
                    val date = java.util.Date(offlinePlayer.firstPlayed)
                    val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm")
                    dateFormat.format(date)
                } else conf.getStatusTranslation("never")

                val lastSeen = if (isOnline) {
                    conf.getStatusTranslation("online")
                } else if (offlinePlayer.lastPlayed > 0) {
                    val date = java.util.Date(offlinePlayer.lastPlayed)
                    val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm")
                    dateFormat.format(date)
                } else {
                    conf.getStatusTranslation("never")
                }

                val deaths = offlinePlayer.getStatistic(org.bukkit.Statistic.DEATHS)
                val level = if (isOnline) Bukkit.getPlayerExact(playerName)?.level ?: 0 else 0

                // Получаем данные репутации
                val repData = ZTele.reputation.getReputationData(playerName)
                val reputation = repData.totalReputation.toString()
                val reputationPositive = repData.positiveRep.toString()
                val reputationNegative = repData.negativeRep.toString()
                val reputationLevel = repData.reputationLevel.emoji + " " + repData.reputationLevel.displayName
                val reputationPercent = String.format("%.1f", repData.positivePercentage)

                if (conf.debugEnabled) {
                    plugin.logger.info("[TBot] /player DEBUG - playerName: '$playerName'")
                    plugin.logger.info("[TBot] /player DEBUG - isOnline: $isOnline")
                    plugin.logger.info("[TBot] /player DEBUG - coords: '$coords'")
                    plugin.logger.info("[TBot] /player DEBUG - balance: '$balance'")
                    plugin.logger.info("[TBot] /player DEBUG - onlineStatus: '$onlineStatus'")
                    plugin.logger.info("[TBot] /player DEBUG - reputation: '$reputation'")
                    plugin.logger.info("[TBot] /player DEBUG - template: '${conf.playerCommandResponse}'")
                }

                val context = PlaceholderEngine.createCustomContext(mapOf(
                    "player" to playerName,
                    "gender" to gender,
                    "balance" to balance,
                    "online" to onlineStatus,
                    "health" to currentHealth.toString(),
                    "registered" to registeredDate,
                    "coords" to coords,
                    "first_played" to firstPlayed,
                    "last_seen" to lastSeen,
                    "deaths" to deaths.toString(),
                    "level" to level.toString(),
                    "reputation" to reputation,
                    "reputation_positive" to reputationPositive,
                    "reputation_negative" to reputationNegative,
                    "reputation_level" to reputationLevel,
                    "reputation_percent" to reputationPercent
                ))

                if (conf.debugEnabled) {
                    plugin.logger.info("[TBot] /player DEBUG - context: ${context.customPlaceholders}")
                }

                val response = PlaceholderEngine.process(conf.playerCommandResponse, context)

                if (conf.debugEnabled) {
                    plugin.logger.info("[TBot] /player DEBUG - PlaceholderEngine result: '$response'")
                }

                sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), response, conf.commandsAutoDeleteSeconds)
            }


            "stats" -> {
                if (!conf.enabledStatsCommand) return

                // Парсим аргументы команды
                val (period, periodStr) = if (arguments.isNotEmpty()) {
                    val arg = arguments.lowercase().trim()
                    when {
                        arg.isEmpty() -> Pair(StatsManager.StatsPeriod.TODAY, "today")
                        else -> {
                            // Пробуем парсить как произвольный период
                            val hours = ZTele.stats.parsePeriodToHours(arg)
                            if (hours != null) {
                                Pair(StatsManager.StatsPeriod.CUSTOM, arg)
                            } else {
                                sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.statsCommandUsage, conf.commandsAutoDeleteSeconds)
                                return
                            }
                        }
                    }
                } else {
                    // По умолчанию показываем статистику за сегодня
                    Pair(StatsManager.StatsPeriod.TODAY, "today")
                }

                // Получаем статистику
                val statsResult = if (period == StatsManager.StatsPeriod.CUSTOM) {
                    ZTele.stats.getUniquePlayersCustom(periodStr)
                } else {
                    ZTele.stats.getStats(period)
                }

                if (statsResult.count == 0) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.statsNoPlayers, conf.commandsAutoDeleteSeconds)
                    return
                }

                // Формируем список игроков
                val playersList = statsResult.players.joinToString("\n") { playerName ->
                    "• $playerName"
                }

                // Получаем перевод периода
                val periodTranslation = when (period) {
                    StatsManager.StatsPeriod.HOUR -> conf.getStatsTranslation("h")
                    StatsManager.StatsPeriod.DAY -> conf.getStatsTranslation("d")
                    StatsManager.StatsPeriod.WEEK -> conf.getStatsTranslation("w")
                    StatsManager.StatsPeriod.MONTH -> conf.getStatsTranslation("m")
                    StatsManager.StatsPeriod.TODAY -> "сегодня"
                    StatsManager.StatsPeriod.CUSTOM -> ZTele.stats.formatPeriodDisplay(periodStr)
                }

                // Используем новый PlaceholderEngine
                val context = PlaceholderEngine.createCustomContext(mapOf(
                    "period" to periodTranslation,
                    "unique_count" to statsResult.count.toString(),
                    "players" to playersList
                ))

                val response = PlaceholderEngine.process(conf.statsMessage, context)

                sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), response, conf.commandsAutoDeleteSeconds)
            }

            "top" -> {
                    if (conf.debugEnabled) {
                        plugin.logger.info("[TBot] Processing /top command with new system")
                    }
                if (!conf.enabledTopCommand) return

                try {
                // Парсим аргументы команды
                    val periodStr = if (arguments.isNotEmpty()) {
                    val arg = arguments.lowercase().trim()
                        if (arg.isEmpty()) {
                            "today"
                        } else {
                            // Валидируем период
                            val hours = ZTele.stats.parsePeriodToHours(arg)
                            if (hours != null) {
                                arg
                            } else {
                                sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.topCommandUsage, conf.commandsAutoDeleteSeconds)
                                return
                        }
                    }
                } else {
                        "today"
                    }

                    // Получаем перевод периода для отображения
                    val periodTranslation = when (periodStr) {
                        "today", "сегодня" -> "сегодня"
                        else -> {
                            val hours = ZTele.stats.parsePeriodToHours(periodStr)
                            if (hours != null) {
                                ZTele.stats.formatPeriodDisplay(periodStr)
                            } else {
                                periodStr
                            }
                        }
                    }

                    // Создаем шаблон с переводом периода
                    val templateWithPeriod = conf.topMessage.replace("%period%", periodTranslation)

                    // Используем новую профессиональную систему
                    val response = TopPlaceholderProcessor.processPlaytimeTop(templateWithPeriod, periodStr)

                    if (conf.debugEnabled) {
                        plugin.logger.info("[TBot] Final top message before sending: $response")
                    }
                sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), response, conf.commandsAutoDeleteSeconds)

                } catch (e: Exception) {
                    plugin.logger.severe("Error processing /top command: ${e.message}")
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.topNoData, conf.commandsAutoDeleteSeconds)
                }
            }

            "topbal" -> {
                    if (conf.debugEnabled) {
                        plugin.logger.info("[TBot] Processing /topbal command with new system")
                    }
                if (!conf.enabledTopBalCommand) return

                try {
                    // Используем новую профессиональную систему
                    val response = TopPlaceholderProcessor.processBalanceTop(conf.topBalMessage)

                    if (conf.debugEnabled) {
                        plugin.logger.info("[TBot] Final topbal message before sending: $response")
                    }
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), response, conf.commandsAutoDeleteSeconds)

                } catch (e: Exception) {
                    plugin.logger.severe("Error processing /topbal command: ${e.message}")
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.topBalError, conf.commandsAutoDeleteSeconds)
                }
            }

            "game" -> {
                val parts = arguments.trim().split(" ", limit = 2)
                val subcommand = if (parts.isNotEmpty() && parts[0].isNotEmpty()) parts[0].lowercase() else ""

                when (subcommand) {
                    "stats", "статистика" -> {
                        // Показать статистику игрока
                        val playerName = mgr.getPlayerByTelegramId(userId.toString()) ?: ""
                        if (playerName.isEmpty()) {
                            sendAutoDeleteMessage(getTargetChatId(getGameChannelId()), conf.gameMessageNotRegistered, conf.gameAutoDeleteSeconds)
                            return
                        }

                        // Получаем статистику игрока
                        val stats = ZTele.game.getPlayerStats(userId.toString())
                        val avgTimeSeconds = if (stats.avgTime > 0) (stats.avgTime / 1000.0) else 0.0

                        // Определяем уровень сложности
                        val difficultyLevel = when {
                            stats.totalGames < 5 -> "🟢 Новичок"
                            stats.winRate >= 80 && stats.totalGames >= 20 -> "🔥 Мастер"
                            stats.winRate >= 70 && stats.totalGames >= 15 -> "💎 Эксперт"
                            stats.winRate >= 60 && stats.totalGames >= 10 -> "🔴 Сложно"
                            stats.winRate >= 50 && stats.totalGames >= 8 -> "🟡 Средне"
                            stats.winRate >= 40 && stats.totalGames >= 5 -> "🟠 Легко"
                            else -> "🟢 Новичок"
                        }

                        val statsMessage = """📊 **Ваша статистика в "Угадай слово"**

🎮 **Всего игр:** ${stats.totalGames}
🏆 **Побед:** ${stats.wins}
📉 **Поражений:** ${stats.losses}
📈 **Процент побед:** ${stats.winRate}%
⚡ **Среднее время ответа:** ${String.format("%.1f", avgTimeSeconds)} сек
💰 **Всего заработано:** ${String.format("%.1f", stats.totalEarned)} монет
🎯 **Текущая сложность:** $difficultyLevel

${if (stats.totalGames == 0) "💡 Начните играть с `/game` чтобы улучшить статистику!" else "🔥 Продолжайте играть!"}"""

                        sendAutoDeleteMessage(getTargetChatId(getGameChannelId()), statsMessage, conf.gameAutoDeleteSeconds)
                    }

                    "top", "топ" -> {
                        // Показать топ игроков
                        val topPlayers = ZTele.game.getTopPlayers(10)

                        val topMessage = if (topPlayers.isEmpty()) {
                            """🏆 **Топ игроков "Угадай слово"**

_Топ пока пуст_

🎮 Играйте больше и попадите в топ!"""
                        } else {
                            val topList = topPlayers.mapIndexed { index, (playerName, stats) ->
                                val position = index + 1
                                val medal = when (position) {
                                    1 -> "🥇"
                                    2 -> "🥈"
                                    3 -> "🥉"
                                    else -> "$position."
                                }
                                val avgTimeSeconds = if (stats.avgTime > 0) (stats.avgTime / 1000.0) else 0.0
                                "$medal `$playerName` - ${stats.wins} побед (${stats.winRate}%, ${String.format("%.1f", avgTimeSeconds)}с)"
                            }.joinToString("\n")

                            """🏆 **Топ игроков "Угадай слово"**

$topList

🎮 Играйте больше и попадите в топ!"""
                        }

                        sendAutoDeleteMessage(getTargetChatId(getGameChannelId()), topMessage, conf.gameAutoDeleteSeconds)
                    }

                    "help", "помощь" -> {
                        // Показать справку по игре
                        sendAutoDeleteMessage(getTargetChatId(getGameChannelId()), conf.helpGame.replace("\\n", "\n"), conf.gameAutoDeleteSeconds)
                    }

                    "" -> {
                        // Обычная игра без аргументов
                        val playerName = mgr.getPlayerByTelegramId(userId.toString()) ?: ""

                        if (playerName.isEmpty()) {
                            sendAutoDeleteMessage(getTargetChatId(getGameChannelId()), conf.gameMessageNotRegistered, conf.gameAutoDeleteSeconds)
                            return
                        }

                        // Запускаем игру
                        val gameResponse = ZTele.game.startGame(userId.toString(), playerName)

                        // Отправляем автоудаляемое сообщение с игрой
                        sendAutoDeleteMessage(getTargetChatId(getGameChannelId()), gameResponse, conf.gameAutoDeleteSeconds)
                    }

                    else -> {
                        // Игра с указанным игроком (старая логика)
                        val playerName = subcommand

                        // Запускаем игру
                        val gameResponse = ZTele.game.startGame(userId.toString(), playerName)

                        // Отправляем автоудаляемое сообщение с игрой
                        sendAutoDeleteMessage(getTargetChatId(getGameChannelId()), gameResponse, conf.gameAutoDeleteSeconds)
                    }
                }
            }

            "help", "помощь" -> {
                // Проверяем, есть ли аргумент (например, /help reputation)
                val argParts = arguments.trim().split(" ")
                val helpTopic = if (argParts.isNotEmpty() && argParts[0].isNotEmpty()) argParts[0].lowercase() else ""

                // Контекстная помощь в зависимости от канала или темы
                val helpMessage = when {
                    helpTopic == "reputation" || helpTopic == "репутация" || helpTopic == "rep" -> conf.helpReputation
                    channelType == "main" -> conf.helpMain
                    channelType == "register" -> conf.helpRegister
                    channelType == "game" -> conf.helpGame
                    channelType == "statistics" -> conf.helpStatistics
                    channelType == "console" -> conf.helpConsole
                    else -> conf.helpMain
                }

                val targetChatId = getTargetChatId(when {
                    helpTopic == "reputation" || helpTopic == "репутация" || helpTopic == "rep" -> conf.mainChannelId
                    channelType == "register" -> conf.registerChannelId
                    channelType == "game" -> getGameChannelId()
                    channelType == "statistics" -> getStatisticsChannelId()
                    channelType == "console" -> conf.consoleChannelId
                    else -> conf.mainChannelId
                })

                sendAutoDeleteMessage(targetChatId, helpMessage.replace("\\n", "\n"), conf.commandsAutoDeleteSeconds)
            }

            "unreg", "отменить" -> {
                if (!conf.enabledUnregCommand || channelType != "register") return

                val isAdmin = conf.isAdministrator(userId)
                val chatId = getTargetChatId(conf.registerChannelId)

                // Если команда с аргументом - это админская команда
                if (arguments.isNotEmpty()) {
                    if (!isAdmin) {
                        sendAutoDeleteMessage(chatId, "❌ Только администраторы могут использовать команду `/unreg <никнейм>`", conf.commandsAutoDeleteSeconds)
                    return
                }

                val targetPlayer = arguments.split(" ")[0]

                // Проверяем, зарегистрирован ли игрок
                if (!mgr.isPlayerRegistered(targetPlayer)) {
                    val context = PlaceholderEngine.createCustomContext(mapOf("player" to targetPlayer))
                    val message = PlaceholderEngine.process(conf.unregCommandNotRegistered, context)
                        sendAutoDeleteMessage(chatId, message, conf.commandsAutoDeleteSeconds)
                    return
                }

                // Снимаем регистрацию
                if (mgr.unregisterPlayer(targetPlayer)) {
                    val context = PlaceholderEngine.createCustomContext(mapOf("player" to targetPlayer))
                    val message = PlaceholderEngine.process(conf.unregCommandSuccess, context)
                        sendAutoDeleteMessage(chatId, message, conf.commandsAutoDeleteSeconds)
                    }
                    return
                }

                // Если команда без аргументов - показываем меню подтверждения для обычных игроков
                val currentPlayer = mgr.getPlayerByTelegramId(userId.toString())
                if (currentPlayer == null) {
                    sendAutoDeleteMessage(chatId, "❌ Вы не зарегистрированы.", conf.commandsAutoDeleteSeconds)
                    return
                }

                // Проверяем кулдаун для обычных пользователей
                if (!ZTele.unregCooldowns.canUnregister(userId)) {
                    val remainingTime = ZTele.unregCooldowns.getRemainingTime(userId)
                    val context = PlaceholderEngine.createCustomContext(mapOf("time" to remainingTime))
                    val message = PlaceholderEngine.process(conf.unregCommandCooldown, context)
                    sendAutoDeleteMessage(chatId, message, conf.commandsAutoDeleteSeconds)
                    return
                }

                // Показываем меню подтверждения через RegisterMenuManager
                try {
                    ZTele.registerMenuManager.showUnregisterConfirm(chatId, null, userId)
                } catch (e: Exception) {
                    // Если меню не может быть отправлено, отправляем текстовое сообщение
                    val message = "⚠️ **ПОДТВЕРЖДЕНИЕ ОТМЕНЫ РЕГИСТРАЦИИ**\n\n" +
                            "Вы действительно хотите отменить регистрацию для игрока `$currentPlayer`?\n\n" +
                            "Используйте меню регистрации для подтверждения."
                    sendAutoDeleteMessage(chatId, message, conf.commandsAutoDeleteSeconds)
                }
            }

            "list" -> {
                if (!conf.enabledListCommand || channelType != "register") return

                // Проверяем права администратора
                if (!conf.isAdministrator(userId)) {
                    sendAutoDeleteMessage(getTargetChatId(conf.registerChannelId), conf.errorsNoAdminPermission, conf.commandsAutoDeleteSeconds)
                    return
                }

                val registeredPlayers = mgr.getAllRegisteredPlayers()

                if (registeredPlayers.isEmpty()) {
                    sendAutoDeleteMessage(getTargetChatId(conf.registerChannelId), conf.listCommandEmpty, conf.commandsAutoDeleteSeconds)
                    return
                }

                val message = buildString {
                    // Обрабатываем заголовок с плейсхолдерами
                    val headerContext = PlaceholderEngine.createCustomContext(mapOf("count" to registeredPlayers.size.toString()))
                    append(PlaceholderEngine.process(conf.listCommandHeader, headerContext))
                    append("\n\n")

                    for ((playerName, telegramId) in registeredPlayers) {
                        val entryContext = PlaceholderEngine.createCustomContext(mapOf(
                            "player" to playerName,
                            "telegram_id" to telegramId.toString()
                        ))
                        val entry = PlaceholderEngine.process(conf.listCommandEntry, entryContext)
                        append(entry)
                        append("\n")
                    }

                    append("\n")
                    val footerContext = PlaceholderEngine.createCustomContext(mapOf("count" to registeredPlayers.size.toString()))
                    append(PlaceholderEngine.process(conf.listCommandFooter, footerContext))
                }

                sendAutoDeleteMessage(getTargetChatId(conf.registerChannelId), message.replace("\\n", "\n"), conf.commandsAutoDeleteSeconds)
            }

            "rep" -> {
                // Команда /rep в Telegram показывает информацию о репутации
                val argsTrimmed = arguments.trim()

                if (argsTrimmed.isEmpty()) {
                    // Показываем репутацию самого пользователя
                    val playerName = mgr.getPlayerByTelegramId(userId.toString())

                    if (playerName == null) {
                        sendAutoDeleteMessage(
                            getTargetChatId(conf.mainChannelId),
                            "❌ Вы не зарегистрированы в игре!\nИспользуйте канал регистрации для привязки аккаунта.",
                            conf.commandsAutoDeleteSeconds
                        )
                        return
                    }

                    val repData = ZTele.reputation.getReputationData(playerName)

                    // Формируем сообщение о своей репутации
                    val message = buildString {
                        append("⭐ **Ваша репутация**\n\n")
                        append("${repData.reputationLevel.emoji} Уровень: **${repData.reputationLevel.displayName}**\n")
                        append("📊 Рейтинг: **${repData.totalReputation}**\n")
                        append("👍 Положительная: **${repData.positiveRep}**\n")
                        append("👎 Отрицательная: **${repData.negativeRep}**\n")
                        append("📈 Процент: **${String.format("%.1f", repData.positivePercentage)}%**\n")

                        // Показываем последние изменения
                        val recentEntries = repData.getRecentEntries(3)
                        if (recentEntries.isNotEmpty()) {
                            append("\n📜 **Последние изменения:**\n")
                            for (entry in recentEntries) {
                                val sign = if (entry.isPositive) "+" else "-"
                                val reasonText = if (entry.reason != null) " _\"${entry.reason}\"_" else ""
                                append("  $sign от **${entry.source}**$reasonText\n")
                            }
                        }
                    }

                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.commandsAutoDeleteSeconds)
                    return
                }

                val targetPlayer = argsTrimmed.removePrefix("@").split(" ")[0]
                val repData = ZTele.reputation.getReputationData(targetPlayer)

                // Проверяем, существует ли игрок
                val offlinePlayer = Bukkit.getOfflinePlayer(targetPlayer)
                if (!offlinePlayer.hasPlayedBefore() && Bukkit.getPlayerExact(targetPlayer) == null) {
                    sendAutoDeleteMessage(
                        getTargetChatId(conf.mainChannelId),
                        "❌ Игрок **$targetPlayer** не найден!",
                        conf.commandsAutoDeleteSeconds
                    )
                    return
                }

                // Формируем сообщение о репутации
                val message = buildString {
                    append("⭐ **Репутация игрока $targetPlayer**\n\n")
                    append("${repData.reputationLevel.emoji} Уровень: **${repData.reputationLevel.displayName}**\n")
                    append("📊 Рейтинг: **${repData.totalReputation}**\n")
                    append("👍 Положительная: **${repData.positiveRep}**\n")
                    append("👎 Отрицательная: **${repData.negativeRep}**\n")
                    append("📈 Процент: **${String.format("%.1f", repData.positivePercentage)}%**\n")

                    // Показываем последние изменения
                    val recentEntries = repData.getRecentEntries(3)
                    if (recentEntries.isNotEmpty()) {
                        append("\n📜 **Последние изменения:**\n")
                        for (entry in recentEntries) {
                            val sign = if (entry.isPositive) "+" else "-"
                            val reasonText = if (entry.reason != null) " _\"${entry.reason}\"_" else ""
                            append("  $sign от **${entry.source}**$reasonText\n")
                        }
                    }
                }

                sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.commandsAutoDeleteSeconds)
            }

            "reptop" -> {
                // Топ игроков по репутации
                val topPlayers = ZTele.reputation.getTopPlayers(10)

                if (topPlayers.isEmpty()) {
                    sendAutoDeleteMessage(
                        getTargetChatId(conf.mainChannelId),
                        "📊 **Топ по репутации пуст**\nПока никто не получил репутацию!",
                        conf.commandsAutoDeleteSeconds
                    )
                    return
                }

                val message = buildString {
                    append("🏆 **Топ-10 игроков по репутации**\n\n")

                    topPlayers.forEachIndexed { index, (playerName, repData) ->
                        val position = index + 1
                        val medal = when (position) {
                            1 -> "🥇"
                            2 -> "🥈"
                            3 -> "🥉"
                            else -> "$position."
                        }
                        append("$medal **$playerName** — ${repData.reputationLevel.emoji} **${repData.totalReputation}** ")
                        append("(+${repData.positiveRep} / -${repData.negativeRep})\n")
                    }
                }

                sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.commandsAutoDeleteSeconds)
            }

            "reprecent" -> {
                // Последние изменения репутации
                val recentChanges = ZTele.reputation.getRecentChanges(10)

                if (recentChanges.isEmpty()) {
                    sendAutoDeleteMessage(
                        getTargetChatId(conf.mainChannelId),
                        "📜 **Нет недавних изменений**\nПока никто не получил репутацию!",
                        conf.commandsAutoDeleteSeconds
                    )
                    return
                }

                val message = buildString {
                    append("📜 **Последние изменения репутации**\n\n")

                    for ((targetPlayer, entry) in recentChanges) {
                        val sign = if (entry.isPositive) "+" else "-"
                        val emoji = if (entry.isPositive) "👍" else "👎"
                        val reasonText = if (entry.reason != null) "\n   _\"${entry.reason}\"_" else ""
                        append("$emoji **${entry.source}** → **$targetPlayer** ($sign)$reasonText\n")
                    }
                }

                sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.commandsAutoDeleteSeconds)
            }

            "random" -> {
                if (!conf.enabledRandomCommand) return

                // Проверяем кулдаун
                if (!ZTele.randomManager.canUseRandom(userId)) {
                    val remainingTime = ZTele.randomManager.getRemainingTime(userId)
                    val context = PlaceholderEngine.createCustomContext(mapOf("time" to remainingTime))
                    val message = PlaceholderEngine.process(conf.randomCommandCooldown, context)
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.commandsAutoDeleteSeconds)
                    return
                }

                // Выполняем в асинхронном потоке для получения онлайн игроков
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    try {
                        // Получаем список онлайн игроков
                        val onlinePlayers = Bukkit.getOnlinePlayers()
                        
                        if (onlinePlayers.isEmpty()) {
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.randomCommandNoPlayers, conf.commandsAutoDeleteSeconds)
                            return@Runnable
                        }
                        
                        if (onlinePlayers.size == 1) {
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.randomCommandOnlyOnePlayer, conf.commandsAutoDeleteSeconds)
                            return@Runnable
                        }

                        // Выбираем случайного игрока
                        val winner = ZTele.randomManager.selectRandomPlayer()
                        if (winner == null) {
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.randomCommandNoPlayers, conf.commandsAutoDeleteSeconds)
                            return@Runnable
                        }

                        // Выбираем случайную награду
                        val rewards = conf.randomCommandRewards
                        if (rewards.isEmpty()) {
                            plugin.logger.warning("Random command: No rewards configured!")
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.randomCommandError, conf.commandsAutoDeleteSeconds)
                            return@Runnable
                        }

                        val rewardCommand = ZTele.randomManager.selectRandomReward(rewards)
                        if (rewardCommand == null) {
                            sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.randomCommandError, conf.commandsAutoDeleteSeconds)
                            return@Runnable
                        }

                        // Получаем описание награды
                        val rewardDescriptions = conf.randomCommandRewardDescriptions
                        val rewardIndex = rewards.indexOf(rewardCommand)
                        val rewardDescription = if (rewardIndex >= 0 && rewardIndex < rewardDescriptions.size) {
                            rewardDescriptions[rewardIndex]
                        } else {
                            "награда"
                        }

                        // Устанавливаем кулдаун
                        ZTele.randomManager.setCooldown(userId)

                        // Получаем время для сообщения
                        val now = java.time.LocalDateTime.now(java.time.ZoneId.of("Europe/Moscow"))
                        val timeStr = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

                        // Отправляем сообщение в Telegram
                        val telegramContext = PlaceholderEngine.createCustomContext(mapOf(
                            "player" to winner,
                            "reward" to rewardDescription,
                            "server" to "Zoobastiks.20tps.name",
                            "time" to timeStr
                        ))
                        val telegramMessage = PlaceholderEngine.process(conf.randomCommandWinTelegram, telegramContext)
                        sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), telegramMessage, conf.commandsAutoDeleteSeconds)

                        // Выполняем команду награды в основном потоке
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                // Заменяем плейсхолдер %player% в команде награды
                                val processedRewardCommand = rewardCommand.replace("%player%", winner)
                                
                                // Выполняем команду награды
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedRewardCommand)
                                plugin.logger.info("Random reward executed: $processedRewardCommand for player $winner")
                            } catch (e: Exception) {
                                plugin.logger.severe("Error executing random reward command: ${e.message}")
                                e.printStackTrace()
                            }
                        })

                        // Выполняем команду оповещения в игре
                        val broadcastCommand = conf.randomCommandBroadcastCommand
                        if (broadcastCommand.isNotEmpty()) {
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                try {
                                    val processedBroadcast = broadcastCommand
                                        .replace("%player%", winner)
                                        .replace("%reward%", rewardDescription)
                                    
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedBroadcast)
                                } catch (e: Exception) {
                                    plugin.logger.warning("Error executing broadcast command: ${e.message}")
                                }
                            })
                        }
                    } catch (e: Exception) {
                        plugin.logger.severe("Error in random command: ${e.message}")
                        e.printStackTrace()
                        sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.randomCommandError, conf.commandsAutoDeleteSeconds)
                    }
                })
            }

            "menu" -> {
                if (!conf.menuEnabled) return
                
                try {
                    val chatId = getTargetChatId(conf.mainChannelId)
                    ZTele.menuManager.openMainMenu(chatId, userId, username)
                } catch (e: kotlin.UninitializedPropertyAccessException) {
                    // menuManager еще не инициализирован
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), "⏳ Меню еще не готово. Подождите немного...", conf.commandsAutoDeleteSeconds)
                }
            }

            "pay" -> {
                if (!conf.paymentEnabled) return
                
                // Проверяем, что Vault доступен
                if (ZTele.economy == null) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.paymentCommandVaultNotFound, conf.commandsAutoDeleteSeconds)
                    return
                }
                
                // Проверяем регистрацию
                val playerName = mgr.getPlayerByTelegramId(userId.toString())
                if (playerName == null) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.paymentCommandNotRegistered, conf.commandsAutoDeleteSeconds)
                    return
                }
                
                // Парсим аргументы: /pay ник_игрока сумма
                val args = arguments.trim().split("\\s+".toRegex(), limit = 2)
                if (args.size < 2) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.paymentCommandUsage, conf.commandsAutoDeleteSeconds)
                    return
                }
                
                val toPlayerName = args[0]
                val amountStr = args[1]
                
                // Парсим сумму
                val amount = try {
                    amountStr.replace(",", ".").toDouble()
                } catch (e: NumberFormatException) {
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), conf.paymentCommandInvalidAmount, conf.commandsAutoDeleteSeconds)
                    return
                }
                
                // Выполняем перевод асинхронно
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    val result = ZTele.paymentManager.transferMoney(playerName, toPlayerName, amount)
                    val economy = ZTele.economy
                    val currency = economy?.currencyNamePlural() ?: "монет"
                    
                    val message = if (result.success) {
                        // Успешный перевод
                        val context = org.zoobastiks.ztelegram.utils.PlaceholderEngine.createCustomContext(mapOf(
                            "from_player" to (result.fromPlayer ?: playerName),
                            "to_player" to (result.toPlayer ?: toPlayerName),
                            "amount" to String.format("%.2f", result.amount ?: amount),
                            "balance" to String.format("%.2f", result.newBalance ?: 0.0),
                            "currency" to currency
                        ))
                        org.zoobastiks.ztelegram.utils.PlaceholderEngine.process(conf.paymentCommandSuccess, context)
                    } else {
                        // Ошибка перевода
                        val errorMessage = when (result.errorCode) {
                            "vault_not_found" -> conf.paymentCommandVaultNotFound
                            "invalid_amount" -> conf.paymentCommandInvalidAmount
                            "min_amount" -> {
                                val context = org.zoobastiks.ztelegram.utils.PlaceholderEngine.createCustomContext(mapOf(
                                    "min_amount" to String.format("%.2f", conf.paymentMinAmount),
                                    "currency" to currency
                                ))
                                org.zoobastiks.ztelegram.utils.PlaceholderEngine.process(conf.paymentCommandErrorMinAmount, context)
                            }
                            "max_amount" -> {
                                val context = org.zoobastiks.ztelegram.utils.PlaceholderEngine.createCustomContext(mapOf(
                                    "max_amount" to String.format("%.2f", conf.paymentMaxAmount),
                                    "currency" to currency
                                ))
                                org.zoobastiks.ztelegram.utils.PlaceholderEngine.process(conf.paymentCommandErrorMaxAmount, context)
                            }
                            "same_player" -> conf.paymentCommandErrorSamePlayer
                            "player_not_found" -> {
                                val context = org.zoobastiks.ztelegram.utils.PlaceholderEngine.createCustomContext(mapOf(
                                    "player" to toPlayerName
                                ))
                                org.zoobastiks.ztelegram.utils.PlaceholderEngine.process(conf.paymentCommandErrorPlayerNotFound, context)
                            }
                            "insufficient_funds" -> {
                                val balance = economy?.getBalance(Bukkit.getOfflinePlayer(playerName)) ?: 0.0
                                val context = org.zoobastiks.ztelegram.utils.PlaceholderEngine.createCustomContext(mapOf(
                                    "balance" to String.format("%.2f", balance),
                                    "currency" to currency
                                ))
                                org.zoobastiks.ztelegram.utils.PlaceholderEngine.process(conf.paymentCommandErrorInsufficientFunds, context)
                            }
                            "withdraw_error" -> conf.paymentCommandErrorWithdraw.replace("%error%", result.errorMessage ?: "неизвестная ошибка")
                            "deposit_error" -> conf.paymentCommandErrorDeposit.replace("%error%", result.errorMessage ?: "неизвестная ошибка")
                            else -> conf.paymentCommandErrorGeneral
                        }
                        errorMessage
                    }
                    sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, conf.commandsAutoDeleteSeconds)
                })
            }

        }
    }

    fun getPlayerBalance(playerName: String): Double {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return 0.0

        val rsp = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy::class.java)
        val economy = rsp?.provider ?: return 0.0

        return economy.getBalance(Bukkit.getOfflinePlayer(playerName))
    }

    private fun handleConsoleChannelMessage(text: String, @Suppress("UNUSED_PARAMETER") username: String) {
        if (!conf.consoleChannelEnabled) return

        // Отладочная информация для консольного канала
        val targetChatId = getTargetChatId(conf.consoleChannelId)
        if (conf.debugEnabled) {
            plugin.logger.info("📤 Console reply will be sent to chatId: $targetChatId (configured: ${conf.consoleChannelId})")
        }

        // Проверяем, является ли сообщение специальной командой белого списка
        if (text.startsWith("/whitelist ")) {
            handleWhitelistCommand(text, username)
            return
        }

        // Проверяем, является ли сообщение командой плагина
        if (text.startsWith("/telegram ")) {
            handlePluginCommand(text, username)
            return
        }


        // Если это обычная команда, выполняем ее как консольную команду
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text)

                if (conf.consoleCommandFeedbackEnabled) {
                    val context = PlaceholderEngine.createCustomContext(mapOf(
                        "command" to text,
                        "user" to username
                    ))
                    val response = PlaceholderEngine.process(conf.consoleCommandFeedback, context)

                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), response, conf.consoleAutoDeleteSeconds)
                }
            } catch (e: Exception) {
                val context = PlaceholderEngine.createCustomContext(mapOf(
                    "command" to text,
                    "error" to (e.message ?: "Unknown error")
                ))
                val errorMsg = PlaceholderEngine.process(conf.consoleCommandError, context)

                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
            }
        })
    }

    private fun handleWhitelistCommand(text: String, @Suppress("UNUSED_PARAMETER") username: String) {
        val parts = text.split(" ")

        if (parts.size < 2) {
            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неверный формат команды. Используйте /whitelist [add|remove|on|off|list] [player]", conf.consoleAutoDeleteSeconds)
            return
        }

        val subCommand = parts[1].lowercase()

        when (subCommand) {
            "add" -> {
                if (parts.size < 3) {
                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Не указан игрок. Используйте /whitelist add [player]", conf.consoleAutoDeleteSeconds)
                    return
                }

                val playerName = parts[2]

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Добавляем игрока в whitelist.json Minecraft
                        val whitelistCommand = "whitelist add $playerName"
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), whitelistCommand)

                        // Отправляем сообщение об успехе
                        val response = processPlaceholders(conf.whitelistAddSuccess, mapOf("player" to playerName))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), response, conf.consoleAutoDeleteSeconds)
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        val errorMsg = processPlaceholders(conf.whitelistAddError, mapOf("player" to playerName))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                    }
                })
            }

            "remove" -> {
                if (parts.size < 3) {
                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Не указан игрок. Используйте /whitelist remove [player]", conf.consoleAutoDeleteSeconds)
                    return
                }

                val playerName = parts[2]

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Удаляем игрока из whitelist.json Minecraft
                        val whitelistCommand = "whitelist remove $playerName"
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), whitelistCommand)

                        // Отправляем сообщение об успехе
                        val response = processPlaceholders(conf.whitelistRemoveSuccess, mapOf("player" to playerName))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), response, conf.consoleAutoDeleteSeconds)
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        val errorMsg = processPlaceholders(conf.whitelistRemoveError, mapOf("player" to playerName))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                    }
                })
            }

            "on" -> {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Включаем белый список
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist on")

                        // Отправляем сообщение об успехе
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.whitelistOn, conf.consoleAutoDeleteSeconds)
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Ошибка при включении белого списка.", conf.consoleAutoDeleteSeconds)
                    }
                })
            }

            "off" -> {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Выключаем белый список
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist off")

                        // Отправляем сообщение об успехе
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.whitelistOff, conf.consoleAutoDeleteSeconds)
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Ошибка при отключении белого списка.", conf.consoleAutoDeleteSeconds)
                    }
                })
            }

            "list" -> {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Получаем список игроков из белого списка
                        val whitelist = Bukkit.getWhitelistedPlayers()

                        if (whitelist.isEmpty()) {
                            // Если белый список пуст
                            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.whitelistListEmpty, conf.consoleAutoDeleteSeconds)
                        } else {
                            // Формируем сообщение со списком игроков
                            val sb = StringBuilder(conf.whitelistListHeader)
                            sb.append("\n")

                            for (player in whitelist) {
                                sb.append(processPlaceholders(conf.whitelistListEntry, mapOf("player" to (player.name ?: "Unknown"))))
                                sb.append("\n")
                            }

                            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), sb.toString(), conf.consoleAutoDeleteSeconds)
                        }
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Ошибка при получении списка игроков.", conf.consoleAutoDeleteSeconds)
                    }
                })
            }

            else -> {
                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неизвестная подкоманда. Доступные команды: /whitelist [add|remove|on|off|list] [player]", conf.consoleAutoDeleteSeconds)
            }
        }
    }

    private fun handlePluginCommand(text: String, @Suppress("UNUSED_PARAMETER") username: String) {
        val parts = text.split(" ")

        if (parts.size < 2) {
            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неверный формат команды. Используйте /telegram [subcommand]", conf.consoleAutoDeleteSeconds)
            return
        }

        val subCommand = parts[1].lowercase()

        when (subCommand) {
            "addchannel" -> {
                if (parts.size < 4) {
                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неверный формат команды. Используйте /telegram addchannel <1|2|3> <channelId>", conf.consoleAutoDeleteSeconds)
                    return
                }

                val channelNumber = parts[2].toIntOrNull()
                if (channelNumber == null || channelNumber < 1 || channelNumber > 3) {
                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неверный номер канала. Используйте 1, 2 или 3.", conf.consoleAutoDeleteSeconds)
                    return
                }

                val channelId = parts[3]

                // Выполняем команду в главном потоке
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Обновляем ID канала в конфигурации
                        val config = plugin.config
                        when (channelNumber) {
                            1 -> {
                                config.set("channels.main", channelId)
                                conf.mainChannelId = channelId
                            }
                            2 -> {
                                config.set("channels.console", channelId)
                                conf.consoleChannelId = channelId
                            }
                            3 -> {
                                config.set("channels.register", channelId)
                                conf.registerChannelId = channelId
                            }
                        }

                        plugin.saveConfig()

                        // Отправляем сообщение об успехе
                        val response = processPlaceholders(conf.pluginAddChannelSuccess, mapOf(
                            "channel_number" to channelNumber.toString(),
                            "channel_id" to channelId
                        ))

                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), response, conf.consoleAutoDeleteSeconds)
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                    }
                })
            }

            "addplayer" -> {
                if (parts.size < 3) {
                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неверный формат команды. Используйте /telegram addplayer <player>", conf.consoleAutoDeleteSeconds)
                    return
                }

                val playerName = parts[2]

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Если игрок уже скрыт, отправляем ошибку
                        if (mgr.isPlayerHidden(playerName)) {
                            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Игрок $playerName уже скрыт в сообщениях Telegram.", conf.consoleAutoDeleteSeconds)
                            return@Runnable
                        }

                        // Добавляем игрока в список скрытых
                        mgr.addHiddenPlayer(playerName)

                        // Отправляем сообщение об успехе
                        val response = processPlaceholders(conf.pluginAddPlayerSuccess, mapOf("player" to playerName))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), response, conf.consoleAutoDeleteSeconds)
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                    }
                })
            }

            "removeplayer" -> {
                if (parts.size < 3) {
                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неверный формат команды. Используйте /telegram removeplayer <player>", conf.consoleAutoDeleteSeconds)
                    return
                }

                val playerName = parts[2]

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Если игрок не скрыт, отправляем ошибку
                        if (!mgr.isPlayerHidden(playerName)) {
                            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Игрок $playerName не скрыт в сообщениях Telegram.", conf.consoleAutoDeleteSeconds)
                            return@Runnable
                        }

                        // Удаляем игрока из списка скрытых
                        mgr.removeHiddenPlayer(playerName)

                        // Отправляем сообщение об успехе
                        val response = processPlaceholders(conf.pluginRemovePlayerSuccess, mapOf("player" to playerName))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), response, conf.consoleAutoDeleteSeconds)
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                    }
                })
            }

            "reload" -> {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Перезагружаем конфигурацию плагина
                        plugin.reloadConfig()
                        conf.reload()

                        // Отправляем сообщение об успехе
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.pluginReloadSuccess, conf.consoleAutoDeleteSeconds)
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                    }
                })
            }

            "unregister" -> {
                if (parts.size < 3) {
                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неверный формат команды. Используйте /telegram unregister <player>", conf.consoleAutoDeleteSeconds)
                    return
                }

                val playerName = parts[2]

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Проверяем, зарегистрирован ли игрок
                        if (!mgr.isPlayerRegistered(playerName)) {
                            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), processPlaceholders(conf.pluginUnregisterNotRegistered, mapOf("player" to playerName)), conf.consoleAutoDeleteSeconds)
                            return@Runnable
                        }

                        // Отменяем регистрацию игрока
                        mgr.unregisterPlayer(playerName)

                        // Отправляем сообщение об успехе
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), processPlaceholders(conf.pluginUnregisterSuccess, mapOf("player" to playerName)), conf.consoleAutoDeleteSeconds)
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                    }
                })
            }

            "hidden" -> {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Получаем список скрытых игроков
                        val hiddenPlayers = mgr.getHiddenPlayers()

                        if (hiddenPlayers.isEmpty()) {
                            // Если список пуст
                            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.pluginHiddenEmpty, conf.consoleAutoDeleteSeconds)
                        } else {
                            // Формируем сообщение со списком скрытых игроков
                            val sb = StringBuilder(conf.pluginHiddenHeader)
                            sb.append("\n")

                            for (player in hiddenPlayers) {
                                sb.append("  • $player")
                                sb.append("\n")
                            }

                            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), sb.toString(), conf.consoleAutoDeleteSeconds)
                        }
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                    }
                })
            }

            "whitelist" -> {
                if (parts.size < 3) {
                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неверный формат команды. Используйте /telegram whitelist <add|remove|list|on|off> [player]", conf.consoleAutoDeleteSeconds)
                    return
                }

                val whitelistCommand = parts[2].lowercase()

                when (whitelistCommand) {
                    "add" -> {
                        if (parts.size < 4) {
                            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Не указан ID пользователя. Используйте /telegram whitelist add <userId>", conf.consoleAutoDeleteSeconds)
                            return
                        }

                        val userId = parts[3]

                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                // Добавляем пользователя в белый список
                                if (mgr.isPlayerWhitelisted(userId)) {
                                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), processPlaceholders(conf.pluginWhitelistAddAlready, mapOf("user_id" to userId)), conf.consoleAutoDeleteSeconds)
                                    return@Runnable
                                }

                                mgr.addPlayerToWhitelist(userId)

                                // Отправляем сообщение об успехе
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), processPlaceholders(conf.pluginWhitelistAddSuccess, mapOf("user_id" to userId)), conf.consoleAutoDeleteSeconds)
                            } catch (e: Exception) {
                                // Отправляем сообщение об ошибке
                                val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                            }
                        })
                    }

                    "remove" -> {
                        if (parts.size < 4) {
                            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Не указан ID пользователя. Используйте /telegram whitelist remove <userId>", conf.consoleAutoDeleteSeconds)
                            return
                        }

                        val userId = parts[3]

                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                // Удаляем пользователя из белого списка
                                if (!mgr.isPlayerWhitelisted(userId)) {
                                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), processPlaceholders(conf.pluginWhitelistRemoveNotFound, mapOf("user_id" to userId)), conf.consoleAutoDeleteSeconds)
                                    return@Runnable
                                }

                                mgr.removePlayerFromWhitelist(userId)

                                // Отправляем сообщение об успехе
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), processPlaceholders(conf.pluginWhitelistRemoveSuccess, mapOf("user_id" to userId)), conf.consoleAutoDeleteSeconds)
                            } catch (e: Exception) {
                                // Отправляем сообщение об ошибке
                                val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                            }
                        })
                    }

                    "list" -> {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                // Получаем список игроков из белого списка
                                val whitelistedPlayers = mgr.getWhitelistedPlayers()

                                if (whitelistedPlayers.isEmpty()) {
                                    // Если белый список пуст
                                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.pluginWhitelistListEmpty, conf.consoleAutoDeleteSeconds)
                                } else {
                                    // Формируем сообщение со списком пользователей
                                    val sb = StringBuilder(conf.pluginWhitelistListHeader)
                                    sb.append("\n")

                                    for ((userId, playerName) in whitelistedPlayers) {
                                        val displayName = if (playerName.isNotEmpty()) "$userId ($playerName)" else userId
                                        sb.append("  • $displayName")
                                        sb.append("\n")
                                    }

                                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), sb.toString(), conf.consoleAutoDeleteSeconds)
                                }
                            } catch (e: Exception) {
                                // Отправляем сообщение об ошибке
                                val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                            }
                        })
                    }

                    "on" -> {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                // Включаем белый список телеграм
                                val config = plugin.config
                                config.set("chat.whitelist.enabled", true)
                                plugin.saveConfig()
                                conf.chatWhitelistEnabled = true
                                conf.whitelistEnabled = true

                                // Отправляем сообщение об успехе
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.pluginWhitelistOnSuccess, conf.consoleAutoDeleteSeconds)
                            } catch (e: Exception) {
                                // Отправляем сообщение об ошибке
                                val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                            }
                        })
                    }

                    "off" -> {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                // Выключаем белый список телеграм
                                val config = plugin.config
                                config.set("chat.whitelist.enabled", false)
                                plugin.saveConfig()
                                conf.chatWhitelistEnabled = false
                                conf.whitelistEnabled = false

                                // Отправляем сообщение об успехе
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.pluginWhitelistOffSuccess, conf.consoleAutoDeleteSeconds)
                            } catch (e: Exception) {
                                // Отправляем сообщение об ошибке
                                val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                            }
                        })
                    }

                    else -> {
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неизвестная подкоманда. Доступные команды: /telegram whitelist [add|remove|list|on|off] [player]", conf.consoleAutoDeleteSeconds)
                    }
                }
            }

            "blacklist" -> {
                if (parts.size < 3) {
                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неверный формат команды. Используйте /telegram blacklist <add|remove|list|on|off> [player]", conf.consoleAutoDeleteSeconds)
                    return
                }

                val blacklistCommand = parts[2].lowercase()

                when (blacklistCommand) {
                    "add" -> {
                        if (parts.size < 4) {
                            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Не указан ID пользователя. Используйте /telegram blacklist add <userId>", conf.consoleAutoDeleteSeconds)
                            return
                        }

                        val userId = parts[3]

                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                // Добавляем пользователя в черный список
                                if (mgr.isPlayerBlacklisted(userId)) {
                                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), processPlaceholders(conf.pluginBlacklistAddAlready, mapOf("user_id" to userId)), conf.consoleAutoDeleteSeconds)
                                    return@Runnable
                                }

                                mgr.addPlayerToBlacklist(userId)

                                // Отправляем сообщение об успехе
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), processPlaceholders(conf.pluginBlacklistAddSuccess, mapOf("user_id" to userId)), conf.consoleAutoDeleteSeconds)
                            } catch (e: Exception) {
                                // Отправляем сообщение об ошибке
                                val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                            }
                        })
                    }

                    "remove" -> {
                        if (parts.size < 4) {
                            sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Не указан ID пользователя. Используйте /telegram blacklist remove <userId>", conf.consoleAutoDeleteSeconds)
                            return
                        }

                        val userId = parts[3]

                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                // Удаляем пользователя из черного списка
                                if (!mgr.isPlayerBlacklisted(userId)) {
                                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), processPlaceholders(conf.pluginBlacklistRemoveNotFound, mapOf("user_id" to userId)), conf.consoleAutoDeleteSeconds)
                                    return@Runnable
                                }

                                mgr.removePlayerFromBlacklist(userId)

                                // Отправляем сообщение об успехе
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), processPlaceholders(conf.pluginBlacklistRemoveSuccess, mapOf("user_id" to userId)), conf.consoleAutoDeleteSeconds)
                            } catch (e: Exception) {
                                // Отправляем сообщение об ошибке
                                val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                            }
                        })
                    }

                    "list" -> {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                // Получаем список игроков из черного списка
                                val blacklistedPlayers = mgr.getBlacklistedPlayers()

                                if (blacklistedPlayers.isEmpty()) {
                                    // Если черный список пуст
                                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.pluginBlacklistListEmpty, conf.consoleAutoDeleteSeconds)
                                } else {
                                    // Формируем сообщение со списком пользователей
                                    val sb = StringBuilder(conf.pluginBlacklistListHeader)
                                    sb.append("\n")

                                    for ((userId, playerName) in blacklistedPlayers) {
                                        val displayName = if (playerName.isNotEmpty()) "$userId ($playerName)" else userId
                                        sb.append("  • $displayName")
                                        sb.append("\n")
                                    }

                                    sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), sb.toString(), conf.consoleAutoDeleteSeconds)
                                }
                            } catch (e: Exception) {
                                // Отправляем сообщение об ошибке
                                val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                            }
                        })
                    }

                    "on" -> {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                // Включаем черный список телеграм
                                val config = plugin.config
                                config.set("chat.blacklist.enabled", true)
                                plugin.saveConfig()
                                conf.chatBlacklistEnabled = true
                                conf.blacklistEnabled = true

                                // Отправляем сообщение об успехе
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.pluginBlacklistOnSuccess, conf.consoleAutoDeleteSeconds)
                            } catch (e: Exception) {
                                // Отправляем сообщение об ошибке
                                val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                            }
                        })
                    }

                    "off" -> {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                // Выключаем черный список телеграм
                                val config = plugin.config
                                config.set("chat.blacklist.enabled", false)
                                plugin.saveConfig()
                                conf.chatBlacklistEnabled = false
                                conf.blacklistEnabled = false

                                // Отправляем сообщение об успехе
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.pluginBlacklistOffSuccess, conf.consoleAutoDeleteSeconds)
                            } catch (e: Exception) {
                                // Отправляем сообщение об ошибке
                                val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                                sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                            }
                        })
                    }

                    else -> {
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), "❌ Неизвестная подкоманда. Доступные команды: /telegram blacklist [add|remove|list|on|off] [player]", conf.consoleAutoDeleteSeconds)
                    }
                }
            }


            else -> {
                // Для неизвестных команд выполняем как обычную команду
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text)

                        // Отправляем сообщение об информации о плагине
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), conf.pluginTelegramInfo, conf.consoleAutoDeleteSeconds)
                    } catch (e: Exception) {
                        // Отправляем сообщение об ошибке
                        val errorMsg = processPlaceholders(conf.pluginCommandError, mapOf("error" to (e.message ?: "Unknown error")))
                        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), errorMsg, conf.consoleAutoDeleteSeconds)
                    }
                })
            }
        }
    }

    private fun isValidMinecraftUsername(username: String): Boolean {
        // Проверяем, что никнейм содержит только английские буквы, цифры и подчеркивания
        // Длина должна быть от 3 до 16 символов (стандарт Minecraft)
        val validPattern = Regex("^[a-zA-Z0-9_]{3,16}$")
        return validPattern.matches(username)
    }

    private fun handleRegisterChannelMessage(message: Message, user: User) {
        val messageText = message.text ?: return

        // Используем правильный chatId для отправки ответов в ту же тему
        val chatId = getTargetChatId(conf.registerChannelId)
        if (conf.debugEnabled) {
            plugin.logger.info("📤 Register reply will be sent to chatId: $chatId (configured: ${conf.registerChannelId})")
        }

        // Используем originalText для сохранения оригинального написания ника
        val originalText = messageText.trim()

        // Сначала проверяем, является ли сообщение командой
        if (originalText.startsWith("/")) {
            val commandParts = originalText.split(" ", limit = 2)
            val command = commandParts[0].substring(1).lowercase()
            val arguments = if (commandParts.size > 1) commandParts[1] else ""
            val username = user.userName ?: user.firstName

            // Если команда /menu - показываем меню регистрации
            if (command == "menu") {
                try {
                    ZTele.registerMenuManager.showMainMenu(chatId, null, user.id, username)
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to show register menu: ${e.message}")
                }
                return
            }

            executeCommand(command, arguments, username, user.id, "register")
            return
        }

        // Проверяем, что пользователь не зарегистрирован
        val existingPlayer = mgr.getPlayerByTelegramId(user.id.toString())
        if (existingPlayer != null) {
            sendMessage(
                chatId,
                processPlaceholders(conf.registerUserAlreadyRegistered, mapOf("player" to existingPlayer))
            )
            return
        }

        // ПРИОРИТЕТ 1: Сначала пытаемся зарегистрировать как никнейм игрока
        // Проверяем валидность никнейма (длина 3-16, буквы, цифры, подчеркивание)
        if (isValidMinecraftUsername(originalText)) {
            plugin.logger.info("Attempting username registration: $originalText from user: ${user.id}")

            // Проверяем, не зарегистрирован ли уже этот никнейм
            if (mgr.isPlayerRegistered(originalText)) {
                sendMessage(
                    chatId,
                    processPlaceholders(conf.registerAlreadyRegistered, mapOf("player" to originalText))
                )
                return
            }

            // Регистрируем игрока
            val registrationResult = mgr.registerPlayer(originalText, user.id.toString())
            if (registrationResult) {
                plugin.logger.info("✅ Successfully registered player $originalText with telegramId: ${user.id}")

                // Отправляем сообщение об успешной регистрации в Telegram
                sendMessage(
                    chatId,
                    processPlaceholders(conf.registerSuccess, mapOf("player" to originalText))
                )

                // Отправляем сообщение об успешной регистрации игроку в игре
                val player = Bukkit.getPlayerExact(originalText)
                if (player != null) {
                    sendComponentToPlayer(player, conf.registerSuccessInGame)
                }

                // Выполняем команды награды
                executeRewardCommands(originalText)
                return
            } else {
                plugin.logger.warning("Failed to register player $originalText with telegramId: ${user.id}")
                sendMessage(
                    chatId,
                    processPlaceholders(conf.registerInvalidUsername, mapOf("player" to originalText))
                )
                return
            }
        }

        // ПРИОРИТЕТ 2: Если не подходит как никнейм, пытаемся обработать как код регистрации
        // Код должен быть точной длины и содержать только буквы и цифры
        if (originalText.length == conf.linkCodeLength && originalText.matches(Regex("^[a-zA-Z0-9]+$"))) {
            plugin.logger.info("Attempting code registration: $originalText from user: ${user.id}")

            // Валидируем код регистрации
            val validationResult = mgr.validateRegistrationCode(originalText, user.id.toString())
            if (validationResult) {
                // Получаем имя игрока после успешной регистрации
                val playerName = mgr.getPlayerByTelegramId(user.id.toString())
                if (playerName != null) {
                    plugin.logger.info("✅ Registration code validated successfully for user: ${user.id}, player: $playerName")

                    // Отправляем сообщение об успешной регистрации в Telegram
                    sendMessage(
                        chatId,
                        processPlaceholders(conf.registerCodeSuccess, mapOf("player" to playerName))
                    )

                    // Отправляем сообщение об успешной регистрации игроку в игре
                    val player = Bukkit.getPlayerExact(playerName)
                    if (player != null) {
                        sendComponentToPlayer(player, conf.registerSuccessInGame)
                    }

                    // Выполняем команды награды
                    executeRewardCommands(playerName)
                } else {
                    plugin.logger.warning("Player not found after successful registration code validation")
                    sendMessage(chatId, conf.messages.commands.linkSuccess)
                }
            } else {
                plugin.logger.info("❌ Invalid registration code from user: ${user.id}")
                sendMessage(chatId, conf.messages.commands.linkInvalid)
            }
        } else {
            // Ни никнейм, ни код - показываем меню регистрации для помощи
            plugin.logger.info("❌ Invalid input (not a valid username or code): $originalText from user: ${user.id}")
            
            // Показываем меню регистрации вместо простого сообщения об ошибке
            try {
                val username = user.userName ?: user.firstName
                ZTele.registerMenuManager.showMainMenu(chatId, null, user.id, username)
            } catch (e: Exception) {
                // Если меню не может быть отправлено, отправляем текстовое сообщение
            sendMessage(
                chatId,
                    processPlaceholders(conf.registerInvalidUsername, mapOf("player" to originalText)) +
                            "\n\n💡 Используйте команду /menu для открытия меню регистрации."
            )
            }
        }
    }

    // Выполняет команды награды за регистрацию
    private fun executeRewardCommands(playerName: String) {
        if (conf.registerRewardCommands.isEmpty()) {
            plugin.logger.warning("No reward commands configured for registration.")
            return
        }

        plugin.logger.info("Executing reward commands for player: $playerName")
        plugin.logger.info("Commands to execute: ${conf.registerRewardCommands}")

        val server = Bukkit.getServer()
        for (command in conf.registerRewardCommands) {
            try {
                // Заменяем плейсхолдер игрока
                val parsedCommand = processPlaceholders(command, mapOf("player" to playerName))

                plugin.logger.info("Executing reward command: $parsedCommand")

                // Отправляем команду в основной поток для выполнения
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        // Выполняем команду от имени консоли
                        server.dispatchCommand(server.consoleSender, parsedCommand)
                        plugin.logger.info("Successfully executed reward command: $parsedCommand")
                    } catch (e: Exception) {
                        plugin.logger.severe("Error executing reward command in main thread: ${e.message}")
                        e.printStackTrace()
                    }
                })
            } catch (e: Exception) {
                plugin.logger.severe("Error preparing reward command for $playerName: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // Отправляет отформатированное сообщение игроку
    private fun sendComponentToPlayer(player: org.bukkit.entity.Player, message: String) {
        if (message.contains("<") && message.contains(">")) {
            // Если есть MiniMessage теги (градиенты и др.)
            val component = GradientUtils.parseMixedFormat(message)
            player.sendMessage(component)
        } else {
            // Для обычных цветовых кодов
            player.sendMessage(ColorUtils.translateColorCodes(message))
        }
    }

    fun sendServerStartMessage() {
        if (!conf.mainChannelEnabled || !conf.serverStartEnabled) return

        sendToMainChannel(conf.serverStartMessage.replace("\\n", "\n"))
    }

    fun sendServerStopMessage() {
        if (!conf.mainChannelEnabled || !conf.serverStopEnabled) return

        sendToMainChannel(conf.serverStopMessage.replace("\\n", "\n"))
    }

    fun sendPlayerJoinMessage(playerName: String) {
        if (!conf.mainChannelEnabled || !conf.chatPlayerJoinEnabled || mgr.isPlayerHidden(playerName)) return

        val context = PlaceholderEngine.createCustomContext(mapOf("player" to playerName))
        val message = PlaceholderEngine.process(conf.chatPlayerJoinMessage, context)
        sendToMainChannel(message)
    }

    fun sendPlayerQuitMessage(playerName: String) {
        if (!conf.mainChannelEnabled || !conf.chatPlayerQuitEnabled || mgr.isPlayerHidden(playerName)) return

        val context = PlaceholderEngine.createCustomContext(mapOf("player" to playerName))
        val message = PlaceholderEngine.process(conf.chatPlayerQuitMessage, context)
        sendToMainChannel(message)
    }

    fun sendPlayerDeathMessage(playerName: String, deathMessage: String) {
        if (!conf.mainChannelEnabled || !conf.chatPlayerDeathEnabled || mgr.isPlayerHidden(playerName)) return

        // Получаем оригинальное сообщение о смерти
        var processedDeathMessage = deathMessage

        // Пробуем убрать имя игрока из сообщения о смерти, если оно там есть
        if (processedDeathMessage.contains(playerName)) {
            processedDeathMessage = processedDeathMessage.replace(playerName, "")
        }

        // Если сообщение начинается с лишних символов (часто остаются после удаления имени)
        processedDeathMessage = processedDeathMessage.trimStart(' ', '.', ',', ':')

        // Добавляем дополнительную обработку для пустого сообщения
        if (processedDeathMessage.isBlank()) {
            // Для пустого сообщения устанавливаем стандартное
            processedDeathMessage = "неизвестных причин"
        }

        // Логирование для отладки (отключено для уменьшения спама в консоли)
        // plugin.logger.info("Death message for $playerName: Original='$deathMessage', Processed='$processedDeathMessage'")

        val context = PlaceholderEngine.createCustomContext(mapOf(
            "player" to playerName,
            "reason" to processedDeathMessage
        ))
        val message = PlaceholderEngine.process(conf.chatPlayerDeathMessage, context)

        sendToMainChannel(message)
    }

    fun handleRendering(playerName: String, text: String) {
        val player = Bukkit.getPlayerExact(playerName)
        if (player == null) {
            sendAutoDeleteMessage(conf.mainChannelId, "❌ Игрок $playerName не в сети!", conf.commandsAutoDeleteSeconds)
            return
        }
        
        when {
            text.equals("[item]", true) -> {
                val item = player.inventory.itemInMainHand
                if (item.type != Material.AIR) {
                    try {
                        val renderer = ItemRenderer()
                        val imageBytes = renderer.renderItemToFile(item).first
                        val itemName = item.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
                        val caption = "$playerName: [$itemName]"
                        sendPhoto(conf.mainChannelId, imageBytes, caption)
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed to render item: ${e.message}")
                        sendAutoDeleteMessage(conf.mainChannelId, "❌ Не удалось отрендерить предмет", conf.commandsAutoDeleteSeconds)
                    }
                } else {
                    sendAutoDeleteMessage(conf.mainChannelId, "❌ У вас нет предмета в руке!", conf.commandsAutoDeleteSeconds)
                }
            }
            
            text.equals("[inv]", true) -> {
                try {
                    val renderer = InventoryRenderer()
                    val imageBytes = renderer.renderInventoryToFile(player.inventory)
                    sendPhoto(conf.mainChannelId, imageBytes, "$playerName: Инвентарь")
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to render inventory: ${e.message}")
                    sendAutoDeleteMessage(conf.mainChannelId, "❌ Не удалось отрендерить инвентарь", conf.commandsAutoDeleteSeconds)
                }
            }
            
            text.equals("[ender]", true) -> {
                try {
                    val renderer = EnderChestRenderer()
                    val imageBytes = renderer.renderEnderChest(player.enderChest)
                    sendPhoto(conf.mainChannelId, imageBytes, "$playerName: Эндер-сундук")
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to render ender chest: ${e.message}")
                    sendAutoDeleteMessage(conf.mainChannelId, "❌ Не удалось отрендерить эндер-сундук", conf.commandsAutoDeleteSeconds)
                }
            }
        }
    }
    
    fun sendPlayerChatMessage(playerName: String, chatMessage: String) {
        if (conf.debugEnabled) {
            plugin.logger.info("=== sendPlayerChatMessage DEBUG ===")
            plugin.logger.info("chatMessage: $chatMessage")
            plugin.logger.info("gameChatsEnabled: ${conf.gameChatsEnabled}")
            plugin.logger.info("gameChatsMinecraftToTelegram: ${conf.gameChatsMinecraftToTelegram}")
        }

        if (!conf.mainChannelEnabled || !conf.chatPlayerChatEnabled || mgr.isPlayerHidden(playerName)) return

        if (conf.gameChatsEnabled && conf.gameChatsMinecraftToTelegram) {
            val matchedChat = ZTele.chatManager.getChatByPrefix(chatMessage)
            val targetChat = matchedChat ?: ZTele.chatManager.getDefaultChat()

            if (targetChat != null && targetChat.enabled) {
                val processedMessage = if (targetChat.prefix.isNotEmpty() && chatMessage.startsWith(targetChat.prefix)) {
                    chatMessage.substring(targetChat.prefix.length).trim()
                } else {
                    chatMessage
                }

                val formattedMessage = targetChat.telegramFormat
                    .replace("<username>", playerName)
                    .replace("<text>", processedMessage)

                val targetChatId = if (targetChat.topicId > 0) {
                    "${targetChat.chatId}_${targetChat.topicId}"
                } else {
                    targetChat.chatId.toString()
                }

                sendAutoDeleteMessage(targetChatId, formattedMessage, 0)
                return
            }
        }

        val context = PlaceholderEngine.createCustomContext(mapOf(
            "player" to playerName,
            "message" to chatMessage
        ))
        val message = PlaceholderEngine.process(conf.chatMinecraftToTelegramFormat, context)
        sendAutoDeleteMessage(conf.mainChannelId, message, 0)
    }

    fun sendPlayerCommandMessage(playerName: String, command: String) {
        // Для консольного канала не проверяем isPlayerHidden, чтобы видеть команды скрытых игроков
        if (!conf.consoleChannelEnabled || !conf.playerCommandLogEnabled) return

        // Используем часовой пояс МСК (+3) для времени
        val now = LocalDateTime.now(java.time.ZoneId.of("Europe/Moscow"))
        val timestamp = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        val context = PlaceholderEngine.createCustomContext(mapOf(
            "time" to timestamp,
            "player" to playerName,
            "command" to command
        ))
        val message = PlaceholderEngine.process(conf.playerCommandLogFormat, context)

        sendToConsoleChannel(message)
    }

    fun sendToMainChannel(message: String) {
        sendMessage(getTargetChatId(conf.mainChannelId), message)
    }

    fun sendToConsoleChannel(message: String) {
        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), message, conf.consoleAutoDeleteSeconds)
    }

    fun sendToRegisterChannel(message: String) {
        sendMessage(getTargetChatId(conf.registerChannelId), message)
    }

    private fun sendMessage(chatId: String, message: String) {
        if (chatId.isEmpty() || message.isEmpty()) return

        // Проверяем состояние соединения перед отправкой
        if (!connectionState.get()) {
            logThrottled("SEND_MESSAGE", "Cannot send message - connection is inactive", "WARNING")
            return
        }

        try {
            // Разбираем chatId на базовый ID и ID темы
            val (baseChatId, threadId) = parseChatId(chatId)
            val sendMessage = SendMessage(baseChatId, convertToHtml(message))

            // Если есть ID темы, устанавливаем его
            if (threadId != null) {
                sendMessage.messageThreadId = threadId
                plugin.logger.info("Sending message to thread $threadId in chat $baseChatId")
            }
            sendMessage.parseMode = "HTML"
            execute(sendMessage)
        } catch (e: TelegramApiException) {
            val isCritical = handleConnectionError(e, "SEND_MESSAGE")

            if (isCritical) {
                // Критические ошибки уже обрабатываются в handleConnectionError
                // Переподключение планируется автоматически
            }
        } catch (e: Exception) {
            handleConnectionError(e, "SEND_MESSAGE_UNEXPECTED")
        }
    }

    /**
     * Отправляет сообщение с inline-клавиатурой (для меню)
     */
    fun sendMenuMessage(chatId: String, text: String, keyboard: InlineKeyboardMarkup): Message? {
        if (chatId.isEmpty() || text.isEmpty()) return null
        
        if (!connectionState.get()) {
            logThrottled("SEND_MENU_MESSAGE", "Cannot send menu message - connection is inactive", "WARNING")
            return null
        }
        
        try {
            val (baseChatId, threadId) = parseChatId(chatId)
            val sendMessage = SendMessage(baseChatId, convertToHtml(text))
            sendMessage.replyMarkup = keyboard
            sendMessage.parseMode = "HTML"
            
            if (threadId != null) {
                sendMessage.messageThreadId = threadId
            }
            
            return execute(sendMessage)
        } catch (e: TelegramApiException) {
            handleConnectionError(e, "SEND_MENU_MESSAGE")
            return null
        } catch (e: Exception) {
            handleConnectionError(e, "SEND_MENU_MESSAGE_UNEXPECTED")
            return null
        }
    }
    
    /**
     * Редактирует сообщение с inline-клавиатурой (для меню)
     */
    fun editMenuMessage(chatId: String, messageId: Int, text: String, keyboard: InlineKeyboardMarkup) {
        if (chatId.isEmpty() || text.isEmpty()) return
        
        if (!connectionState.get()) {
            logThrottled("EDIT_MENU_MESSAGE", "Cannot edit menu message - connection is inactive", "WARNING")
            return
        }
        
        try {
            val (baseChatId, _) = parseChatId(chatId)
            val editMessage = EditMessageText()
            editMessage.chatId = baseChatId
            editMessage.messageId = messageId
            
            // Проверяем длину сообщения (Telegram лимит: 4096 символов)
            val processedText = convertToHtml(text)
            val maxLength = 4096
            val finalText = if (processedText.length > maxLength) {
                plugin.logger.warning("⚠️ [editMenuMessage] Сообщение слишком длинное (${processedText.length} > $maxLength), обрезаем до $maxLength символов")
                processedText.substring(0, maxLength - 3) + "..."
            } else {
                processedText
            }
            
            editMessage.text = finalText
            
            // Проверяем, что keyboard имеет валидную структуру
            val keyboardList = keyboard.keyboard
            if (keyboardList.isNotEmpty()) {
                editMessage.replyMarkup = keyboard
            } else {
                // Если keyboard пустая, создаем пустую клавиатуру
                val emptyKeyboard = InlineKeyboardMarkup()
                emptyKeyboard.keyboard = emptyList()
                editMessage.replyMarkup = emptyKeyboard
            }
            
            editMessage.parseMode = "HTML"
            
            execute(editMessage)
        } catch (e: TelegramApiException) {
            // Обрабатываем rate limiting (429 Too Many Requests)
            if (e is org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException) {
                if (e.errorCode == 429) {
                    val retryAfter = try {
                        e.parameters?.retryAfter ?: 20
                    } catch (ex: Exception) {
                        20
                    }
                    logThrottled("EDIT_MENU_MESSAGE", "Rate limited by Telegram API. Retry after ${retryAfter}s", "WARNING")
                    return
                }
                
                // Обрабатываем ошибку "MESSAGE_TOO_LONG"
                if (e.errorCode == 400 && e.apiResponse?.contains("MESSAGE_TOO_LONG", ignoreCase = true) == true) {
                    plugin.logger.warning("⚠️ [editMenuMessage] Сообщение слишком длинное для Telegram API. Попытка обрезать...")
                    // Пытаемся обрезать сообщение еще больше
                    val processedText = convertToHtml(text)
                    val maxLength = 3000 // Более консервативный лимит
                    val finalText = if (processedText.length > maxLength) {
                        processedText.substring(0, maxLength - 3) + "..."
                    } else {
                        processedText
                    }
                    
                    try {
                        val (baseChatId, _) = parseChatId(chatId)
                        val editMessage = EditMessageText()
                        editMessage.chatId = baseChatId
                        editMessage.messageId = messageId
                        editMessage.text = finalText
                        editMessage.replyMarkup = keyboard
                        editMessage.parseMode = "HTML"
                        execute(editMessage)
                        return
                    } catch (retryException: Exception) {
                        plugin.logger.severe("❌ [editMenuMessage] Не удалось отправить обрезанное сообщение: ${retryException.message}")
                    }
                }
            }
            
            // Игнорируем ошибку "message is not modified" - это нормально
            if (e.message?.contains("message is not modified", ignoreCase = true) != true) {
                handleConnectionError(e, "EDIT_MENU_MESSAGE")
            }
        } catch (e: Exception) {
            handleConnectionError(e, "EDIT_MENU_MESSAGE_UNEXPECTED")
        }
    }
    
    /**
     * Отвечает на callback query
     */
    fun answerCallbackQuery(callbackQueryId: String, text: String? = null, showAlert: Boolean = false) {
        if (!connectionState.get()) {
            logThrottled("ANSWER_CALLBACK", "Cannot answer callback - connection is inactive", "WARNING")
            return
        }
        
        try {
            val answer = AnswerCallbackQuery()
            answer.callbackQueryId = callbackQueryId
            if (text != null) {
                answer.text = text
                answer.showAlert = showAlert
            }
            execute(answer)
        } catch (e: TelegramApiException) {
            // Игнорируем ошибки callback (часто возникают при повторных нажатиях)
            if (conf.debugEnabled) {
                plugin.logger.warning("Error answering callback query: ${e.message}")
            }
        } catch (e: Exception) {
            if (conf.debugEnabled) {
                plugin.logger.warning("Unexpected error answering callback query: ${e.message}")
            }
        }
    }
    
    /**
     * Удаляет сообщение
     */
    fun deleteMessage(chatId: String, messageId: Int) {
        if (chatId.isEmpty()) return
        
        if (!connectionState.get()) {
            logThrottled("DELETE_MESSAGE", "Cannot delete message - connection is inactive", "WARNING")
            return
        }
        
        try {
            val (baseChatId, _) = parseChatId(chatId)
            val deleteMessage = DeleteMessage()
            deleteMessage.chatId = baseChatId
            deleteMessage.messageId = messageId
            
            execute(deleteMessage)
        } catch (e: TelegramApiException) {
            // Игнорируем ошибки удаления (сообщение может быть уже удалено)
            if (conf.debugEnabled) {
                plugin.logger.warning("Error deleting message: ${e.message}")
            }
        } catch (e: Exception) {
            if (conf.debugEnabled) {
                plugin.logger.warning("Unexpected error deleting message: ${e.message}")
            }
        }
    }

    private fun convertTopMessageToHtml(text: String): String {
        // Специальный метод для форматирования топов без дополнительной обработки плейсхолдеров
        var result = text
        // Заменяем **text** на <b>text</b>
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
        // Заменяем `text` на <code>text</code>
        result = result.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        return result
    }

    private fun convertToHtml(text: String): String {
        if (conf.debugEnabled) {
            plugin.logger.info("[TBot] convertToHtml INPUT: $text")
        }
        // Заменяем \n на настоящие переносы строк
        var processedText = text.replace("\\n", "\n")

        // Сохраняем кавычки для моноширинного шрифта
        val codeBlocks = mutableMapOf<String, String>()
        var codeCounter = 0

        // Специальная обработка для маскированных слов в игре "Угадай слово"
        // Если текст содержит маскированное слово (с подчеркиваниями), обрабатываем его как моноширинный текст
        if (processedText.contains("_") && processedText.contains("🎮")) {
            // Находим маскированное слово в сообщении
            val maskedWordRegex = Regex("([А-Яа-яA-Za-z0-9_\\s]+)")
            val maskedWordMatches = maskedWordRegex.findAll(processedText)

            for (match in maskedWordMatches) {
                val word = match.value
                // Проверяем, содержит ли слово подчеркивания (признак маскированного слова)
                if (word.contains("_")) {
                    // Заменяем пробелы на неразрывные пробелы для сохранения форматирования
                    val formattedWord = word.replace(" ", "\u00A0")
                    // Оборачиваем в тег code для моноширинного шрифта
                    val placeholder = "MASKED_WORD_${codeCounter++}"
                    codeBlocks[placeholder] = formattedWord
                    processedText = processedText.replace(word, placeholder)
                }
            }
        }

        // Сохраняем одиночные обратные кавычки для последующей обработки
        processedText = processedText.replace(Regex("`([^`]+)`")) { match ->
            val placeholder = "CODE_BLOCK_${codeCounter++}"
            codeBlocks[placeholder] = match.groupValues[1]
            placeholder
        }

        // Обрабатываем Markdown разметку и запоминаем отформатированные участки
        val formattedParts = mutableMapOf<String, String>()
        var counter = 0

        // Жирный текст - разные варианты
        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] BEFORE BOLD - processedText: '$processedText'")
        }

        processedText = processedText.replace(Regex("\\*\\*(.*?)\\*\\*|<b>(.*?)</b>|<strong>(.*?)</strong>")) { match ->
            val content = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
            val placeholder = "FORMAT_PLACEHOLDER_${counter++}"
            val htmlTag = "<b>$content</b>"
            formattedParts[placeholder] = htmlTag

            if (conf.debugEnabled) {
                plugin.logger.info("[convertToHtml] BOLD DEBUG - match: '${match.value}' -> content: '$content' -> placeholder: '$placeholder' -> htmlTag: '$htmlTag'")
                if (match.value.contains("Координаты") || content.contains("Координаты") || match.value.contains("Никнейм") || content.contains("Никнейм")) {
                    plugin.logger.info("[convertToHtml] SPECIAL BOLD - Found '${match.value}' with content '$content'! Placeholder: $placeholder, HtmlTag: $htmlTag")
                }
            }

            placeholder
        }

        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] AFTER BOLD - processedText: '$processedText'")
        }

        // Курсив - разные варианты
        processedText = processedText.replace(Regex("\\*(.*?)\\*|<i>(.*?)</i>|<em>(.*?)</em>")) { match ->
            val content = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
            val placeholder = "FORMAT_PLACEHOLDER_${counter++}"
            formattedParts[placeholder] = "<i>$content</i>"
            placeholder
        }

        // Моноширинный шрифт (код) - другие варианты
        processedText = processedText.replace(Regex("<code>(.*?)</code>")) { match ->
            val content = match.groupValues[1]
            val placeholder = "FORMAT_PLACEHOLDER_${counter++}"
            formattedParts[placeholder] = "<code>$content</code>"
            placeholder
        }

        // Зачеркнутый текст - разные варианты
        processedText = processedText.replace(Regex("~~(.*?)~~|<s>(.*?)</s>|<strike>(.*?)</strike>|<del>(.*?)</del>")) { match ->
            val content = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
            val placeholder = "FORMAT_PLACEHOLDER_${counter++}"
            formattedParts[placeholder] = "<s>$content</s>"
            placeholder
        }

        // Подчеркнутый текст
        processedText = processedText.replace(Regex("<u>(.*?)</u>|__(.*?)__")) { match ->
            val content = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
            val placeholder = "FORMAT_PLACEHOLDER_${counter++}"
            formattedParts[placeholder] = "<u>$content</u>"
            placeholder
        }

        // Многострочный код с указанием языка
        processedText = processedText.replace(Regex("```([a-zA-Z0-9+]+)?\n(.*?)```", RegexOption.DOT_MATCHES_ALL)) { match ->
            val language = match.groupValues[1].takeIf { it.isNotEmpty() } ?: ""
            val code = match.groupValues[2]
            val placeholder = "FORMAT_PLACEHOLDER_${counter++}"

            if (language.isNotEmpty()) {
                formattedParts[placeholder] = "<pre language=\"$language\">$code</pre>"
            } else {
                formattedParts[placeholder] = "<pre>$code</pre>"
            }

            placeholder
        }

        // Проверяем наличие тега <pre> с атрибутом language
        processedText = processedText.replace(Regex("<pre language=\"([a-zA-Z0-9+]+)\">(.*?)</pre>", RegexOption.DOT_MATCHES_ALL)) { match ->
            val language = match.groupValues[1]
            val code = match.groupValues[2]
            val placeholder = "FORMAT_PLACEHOLDER_${counter++}"
            formattedParts[placeholder] = "<pre language=\"$language\">$code</pre>"
            placeholder
        }

        // Сохраняем плейсхолдеры перед обработкой компонентов
        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] BEFORE SAVE PLACEHOLDERS - processedText: '$processedText'")
        }

        val placeholders = mutableMapOf<String, String>()
        var placeholderCounter = 0
        processedText = processedText.replace(Regex("%([^%]+)%")) { match ->
            val placeholder = "SAVED_PLACEHOLDER_${placeholderCounter++}"
            placeholders[placeholder] = match.value
            if (conf.debugEnabled) {
                plugin.logger.info("[convertToHtml] SAVE PLACEHOLDER - '${match.value}' -> '$placeholder'")
            }
            placeholder
        }

        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] AFTER SAVE PLACEHOLDERS - processedText: '$processedText'")
        }

        // Если текст содержит градиенты или теги MiniMessage
        if (processedText.contains("<") && processedText.contains(">")) {
            if (conf.debugEnabled) {
                plugin.logger.info("[convertToHtml] BEFORE MINIMESSAGE - processedText: '$processedText'")
            }
            try {
                // Обрабатываем MiniMessage форматирование
                val component = GradientUtils.parseMixedFormat(processedText)
                processedText = PlainTextComponentSerializer.plainText().serialize(component)
                if (conf.debugEnabled) {
                    plugin.logger.info("[convertToHtml] MINIMESSAGE SUCCESS - processedText: '$processedText'")
                }
            } catch (e: Exception) {
                if (conf.debugEnabled) {
                    plugin.logger.info("[convertToHtml] MINIMESSAGE FAILED: ${e.message}")
                }
                plugin.logger.warning("Error parsing MiniMessage format: ${e.message}")
            }
        }

        // Для обычных цветовых кодов
        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] BEFORE LEGACY - processedText: '$processedText'")
        }

        try {
            val component = LegacyComponentSerializer.legacySection().deserialize(
                processedText.replace("&", "§")
            )
            processedText = PlainTextComponentSerializer.plainText().serialize(component)
            if (conf.debugEnabled) {
                plugin.logger.info("[convertToHtml] LEGACY SUCCESS - processedText: '$processedText'")
            }
        } catch (e: Exception) {
            if (conf.debugEnabled) {
                plugin.logger.info("[convertToHtml] LEGACY FAILED - stripping colors: ${e.message}")
            }
            // Если произошла ошибка, просто убираем цветовые коды
            processedText = ColorUtils.stripColorCodes(processedText)
            if (conf.debugEnabled) {
                plugin.logger.info("[convertToHtml] AFTER STRIP COLORS - processedText: '$processedText'")
            }
        }

        // Восстанавливаем плейсхолдеры после обработки компонентов
        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] BEFORE RESTORE PLACEHOLDERS - processedText: '$processedText'")
            plugin.logger.info("[convertToHtml] PLACEHOLDERS TO RESTORE: ${placeholders.size}")
            for ((placeholder, originalValue) in placeholders) {
                plugin.logger.info("[convertToHtml] PLACEHOLDER RESTORE - '$placeholder' -> '$originalValue'")
            }
        }

        for ((placeholder, originalValue) in placeholders) {
            val beforeReplace = processedText
            processedText = processedText.replace(placeholder, originalValue)
            if (conf.debugEnabled && beforeReplace != processedText) {
                plugin.logger.info("[convertToHtml] PLACEHOLDER REPLACED - '$placeholder' with '$originalValue'")
            }
        }

        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] AFTER RESTORE PLACEHOLDERS - processedText: '$processedText'")
        }

        // Сохраняем HTML-теги ссылок перед экранированием
        val linkTags = mutableMapOf<String, String>()
        var linkCounter = 0
        processedText = processedText.replace(Regex("<a\\s+href=\"([^\"]+)\">([^<]+)</a>")) { match ->
            val url = match.groupValues[1]
            val linkText = match.groupValues[2]
            val placeholder = "LINK_TAG_${linkCounter++}"
            linkTags[placeholder] = "<a href=\"$url\">$linkText</a>"
            placeholder
        }

        // Экранируем специальные символы HTML
        processedText = processedText
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        
        // Восстанавливаем HTML-теги ссылок после экранирования
        for ((placeholder, linkTag) in linkTags) {
            processedText = processedText.replace(placeholder, linkTag)
        }

        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] BEFORE RESTORE - processedText: '$processedText'")
        }

        // Восстанавливаем placeholders с форматированием
        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] RESTORE DEBUG - formattedParts size: ${formattedParts.size}")
        for ((placeholder, htmlTag) in formattedParts) {
                plugin.logger.info("[convertToHtml] RESTORE DEBUG - $placeholder -> '$htmlTag'")
            }
        }

        // Сортируем плейсхолдеры в ОБРАТНОМ порядке по номеру, чтобы избежать пересечений
        // FORMAT_PLACEHOLDER_10 должен заменяться РАНЬШЕ FORMAT_PLACEHOLDER_1
        val sortedFormattedParts = formattedParts.toList().sortedByDescending { (placeholder, _) ->
            val numberMatch = Regex("FORMAT_PLACEHOLDER_(\\d+)").find(placeholder)
            numberMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] SORTED PLACEHOLDERS:")
            for ((placeholder, htmlTag) in sortedFormattedParts) {
                plugin.logger.info("[convertToHtml] SORTED - $placeholder -> '$htmlTag'")
            }
        }

        for ((placeholder, htmlTag) in sortedFormattedParts) {
            val beforeReplace = processedText
            processedText = processedText.replace(placeholder, htmlTag)
            if (conf.debugEnabled) {
                plugin.logger.info("[convertToHtml] FORMAT REPLACE - '$placeholder' with '$htmlTag'")
                if (beforeReplace != processedText) {
                    plugin.logger.info("[convertToHtml] FORMAT REPLACED SUCCESS - text changed")
                } else {
                    plugin.logger.info("[convertToHtml] FORMAT REPLACED FAILED - text unchanged")
                }
                if (htmlTag.contains("Координаты") || htmlTag.contains("Никнейм")) {
                    plugin.logger.info("[convertToHtml] SPECIAL REPLACE - '$placeholder' -> '$htmlTag'")
                }
            }
        }

        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] AFTER RESTORE - processedText: '$processedText'")
        }

        // Обрабатываем сохраненные блоки кода
        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] BEFORE CODE BLOCKS - processedText: '$processedText'")
        }

        processedText = processedText.replace(Regex("CODE_BLOCK_(\\d+)")) { match ->
            val index = match.groupValues[1].toInt()
            val content = codeBlocks["CODE_BLOCK_$index"] ?: ""
            val result = "<code>$content</code>"
            if (conf.debugEnabled) {
                plugin.logger.info("[convertToHtml] CODE BLOCK RESTORE - '${match.value}' -> '$result'")
            }
            result
        }

        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] AFTER CODE BLOCKS - processedText: '$processedText'")
        }

        // Восстанавливаем маскированные слова
        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] BEFORE MASKED WORDS - processedText: '$processedText'")
        }

        processedText = processedText.replace(Regex("MASKED_WORD_(\\d+)")) { match ->
            val index = match.groupValues[1].toInt()
            val content = codeBlocks["MASKED_WORD_$index"] ?: ""
            val result = "<code>$content</code>"
            if (conf.debugEnabled) {
                plugin.logger.info("[convertToHtml] MASKED WORD RESTORE - '${match.value}' -> '$result'")
            }
            result
        }

        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] AFTER MASKED WORDS - processedText: '$processedText'")
        }

        if (conf.debugEnabled) {
            plugin.logger.info("[convertToHtml] FINAL RESULT - processedText: '$processedText'")
            plugin.logger.info("[TBot] convertToHtml OUTPUT: $processedText")
        }
        return processedText
    }

    // Отправляем приватное сообщение пользователю
    fun sendPrivateMessage(userId: String, message: String) {
        // Проверяем состояние соединения перед отправкой
        if (!connectionState.get()) {
            logThrottled("SEND_PRIVATE", "Cannot send private message - connection is inactive", "WARNING")
            return
        }

        try {
            val sendMessage = SendMessage()
            sendMessage.chatId = userId
            sendMessage.text = convertToHtml(message)
            sendMessage.parseMode = "HTML"

            execute(sendMessage)
        } catch (e: TelegramApiException) {
            val isCritical = handleConnectionError(e, "SEND_PRIVATE_MESSAGE")

            if (isCritical) {
                // Критические ошибки уже обрабатываются в handleConnectionError
                // Переподключение планируется автоматически
            }
        } catch (e: Exception) {
            handleConnectionError(e, "SEND_PRIVATE_MESSAGE_UNEXPECTED")
        }
    }

    // Отправляем сообщение, которое автоматически удалится через указанное время
    fun sendAutoDeleteMessage(chatId: String, message: String, deleteAfterSeconds: Int = 15) {
        if (chatId.isEmpty() || message.isEmpty()) {
            if (message.isEmpty()) {
                plugin.logger.severe("❌ [sendAutoDeleteMessage] Попытка отправить ПУСТОЕ сообщение! chatId: '$chatId'")
                plugin.logger.severe("   [sendAutoDeleteMessage] Это указывает на проблему загрузки конфигурации!")
            }
            if (chatId.isEmpty()) {
                plugin.logger.severe("❌ [sendAutoDeleteMessage] chatId пустой!")
            }
            return
        }

        // Проверяем состояние соединения перед отправкой
        if (!connectionState.get()) {
            logThrottled("SEND_AUTO_DELETE", "Cannot send auto-delete message - connection is inactive", "WARNING")
            return
        }

        try {
            // ВАЖНО: Разбираем chatId на базовый ID и ID темы
            // Если chatId не содержит "_", то threadId будет null и сообщение
            // отправится в основной канал (не в топик)
            val (baseChatId, threadId) = parseChatId(chatId)

            if (conf.debugEnabled) {
                plugin.logger.info("[sendAutoDeleteMessage] Input chatId: '$chatId', parsed baseChatId: '$baseChatId', threadId: $threadId")
            }

            // Отправляем сообщение
            // Используем специальный метод для топов, обычный для остальных сообщений
            val processedMessage = if (message.contains("Топ-10")) {
                convertTopMessageToHtml(message)
            } else {
                convertToHtml(message)
            }
            val sendMessage = SendMessage(baseChatId, processedMessage)
            sendMessage.parseMode = "HTML"

            // Если есть ID темы, устанавливаем его
            // ВАЖНО: threadId будет не null только если chatId содержал "_"
            if (threadId != null) {
                sendMessage.messageThreadId = threadId
                if (conf.debugEnabled) {
                    plugin.logger.info("[sendAutoDeleteMessage] Sending to thread $threadId in chat $baseChatId")
                }
            } else {
                if (conf.debugEnabled) {
                    plugin.logger.info("[sendAutoDeleteMessage] Sending to main chat $baseChatId (no thread)")
                }
            }

            val sentMessage = execute(sendMessage)

            // Планируем удаление сообщения
            if (deleteAfterSeconds > 0) {
                val messageId = sentMessage.messageId

                // Запланировать удаление сообщения через указанное количество секунд
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable {
                    // Проверяем состояние соединения перед удалением
                    if (!connectionState.get()) {
                        logThrottled("DELETE_MESSAGE", "Cannot delete message - connection is inactive")
                        return@Runnable
                    }

                    try {
                        val deleteMessage = DeleteMessage(baseChatId, messageId)
                        execute(deleteMessage)
                    } catch (e: Exception) {
                        // Игнорируем ошибку "message to delete not found" - это нормальная ситуация
                        val errorMessage = e.message ?: ""
                        if (errorMessage.contains("message to delete not found", ignoreCase = true) ||
                            errorMessage.contains("message can't be deleted", ignoreCase = true)) {
                            // Сообщение уже удалено - не логируем это как ошибку
                            if (conf.debugEnabled) {
                                plugin.logger.info("[DELETE_MESSAGE] Message already deleted (messageId: $messageId)")
                            }
                        } else {
                            // Только если это реальная сетевая ошибка
                            handleConnectionError(e, "DELETE_MESSAGE")
                        }
                    }
                }, deleteAfterSeconds * 20L) // Преобразуем секунды в тики (20 тиков = 1 секунда)
            }
        } catch (e: TelegramApiException) {
            val isCritical = handleConnectionError(e, "SEND_AUTO_DELETE")

            if (isCritical) {
                // Критические ошибки уже обрабатываются в handleConnectionError
                // Переподключение планируется автоматически
            }
        } catch (e: Exception) {
            handleConnectionError(e, "SEND_AUTO_DELETE_UNEXPECTED")
        }
    }

    /**
     * Отправляет фото в Telegram
     */
    fun sendPhoto(chatId: String, imageBytes: ByteArray, caption: String? = null) {
        if (conf.debugEnabled) {
            plugin.logger.info("[sendPhoto] chatId: $chatId, image size: ${imageBytes.size}, caption: $caption")
        }
        
        if (!connectionState.get()) {
            plugin.logger.warning("[sendPhoto] Cannot send photo - connection is inactive")
            return
        }
        
        try {
            val (baseChatId, threadId) = parseChatId(chatId)
            val sendPhoto = SendPhoto()
            sendPhoto.chatId = baseChatId
            sendPhoto.photo = InputFile(ByteArrayInputStream(imageBytes), "image.png")
            sendPhoto.parseMode = "HTML"
            
            if (threadId != null) {
                sendPhoto.messageThreadId = threadId
            }
            
            if (caption != null) {
                sendPhoto.caption = caption
            }
            
            execute(sendPhoto)
        } catch (e: TelegramApiException) {
            handleConnectionError(e, "SEND_PHOTO")
        } catch (e: Exception) {
            handleConnectionError(e, "SEND_PHOTO_UNEXPECTED")
        }
    }

    /**
     * Отправляет сообщение с Markdown форматированием и автоудалением
     */
    fun sendMarkdownMessage(chatId: String, message: String, deleteAfterSeconds: Int = 15) {
        if (chatId.isEmpty() || message.isEmpty()) return

        // Проверяем состояние соединения перед отправкой
        if (!connectionState.get()) {
            logThrottled("SEND_MARKDOWN", "Cannot send markdown message - connection is inactive", "WARNING")
            return
        }

        try {
            // Разбираем chatId на базовый ID и ID темы
            val (baseChatId, threadId) = parseChatId(chatId)

            // Отправляем сообщение с Markdown форматированием
            val sendMessage = SendMessage(baseChatId, message)
            sendMessage.parseMode = "Markdown"

            // Если есть ID темы, устанавливаем его
            if (threadId != null) {
                sendMessage.messageThreadId = threadId
                plugin.logger.info("Sending markdown message to thread $threadId in chat $baseChatId")
            }

            val sentMessage = execute(sendMessage)

            // Планируем удаление сообщения
            if (deleteAfterSeconds > 0) {
                val messageId = sentMessage.messageId

                // Запланировать удаление сообщения через указанное количество секунд
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable {
                    // Проверяем состояние соединения перед удалением
                    if (!connectionState.get()) {
                        logThrottled("DELETE_MARKDOWN", "Cannot delete markdown message - connection is inactive")
                        return@Runnable
                    }

                    try {
                        val deleteMessage = DeleteMessage(baseChatId, messageId)
                        execute(deleteMessage)
                    } catch (deleteException: Exception) {
                        // Игнорируем ошибку "message to delete not found"
                        val errorMessage = deleteException.message ?: ""
                        if (!errorMessage.contains("message to delete not found", ignoreCase = true) &&
                            !errorMessage.contains("message can't be deleted", ignoreCase = true)) {
                            logThrottled("DELETE_MARKDOWN_ERROR", "Failed to delete markdown message: ${deleteException.message}")
                        }
                    }
                }, (deleteAfterSeconds * 20).toLong())
            }

        } catch (e: TelegramApiException) {
            handleConnectionError(e, "SEND_MARKDOWN")
        } catch (e: Exception) {
            handleConnectionError(e, "SEND_MARKDOWN_UNEXPECTED")
        }
    }

    /**
     * Планирует автоматические уведомления о топах
     */
    fun scheduleAutoNotifications() {
        if (!conf.autoNotificationsEnabled) return

        // Планируем уведомления о топе по времени игры
        if (conf.playtimeTopAutoEnabled) {
            scheduleTopNotifications(
                schedule = conf.playtimeTopAutoSchedule,
                type = "playtime"
            )
        }

        // Планируем уведомления о топе по балансу
        if (conf.balanceTopAutoEnabled) {
            scheduleTopNotifications(
                schedule = conf.balanceTopAutoSchedule,
                type = "balance"
            )
        }
    }

    /**
     * Планирует уведомления для конкретного типа топа
     */
    private fun scheduleTopNotifications(schedule: String, type: String) {
        val times = schedule.split(",").map { it.trim() }

        // Получаем часовой пояс из конфигурации
        val timezone = try {
            java.time.ZoneId.of(conf.autoNotificationsTimezone)
        } catch (e: Exception) {
            plugin.logger.warning("⚠️ Неверный часовой пояс '${conf.autoNotificationsTimezone}', используется UTC")
            java.time.ZoneId.of("UTC")
        }

        for (timeStr in times) {
            try {
                val (hour, minute) = timeStr.split(":").map { it.toInt() }

                // Вычисляем задержку до следующего запланированного времени с учетом часового пояса
                val now = java.time.ZonedDateTime.now(timezone)
                var nextRun = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)

                // Если время уже прошло сегодня, планируем на завтра
                if (nextRun.isBefore(now) || nextRun.isEqual(now)) {
                    nextRun = nextRun.plusDays(1)
                }

                val delaySeconds = java.time.Duration.between(now, nextRun).seconds
                var delayTicks = delaySeconds * 20 // Конвертируем в тики (20 тиков = 1 секунда)

                // Добавляем смещение для разных типов топов, чтобы избежать одновременной отправки
                // Это предотвращает конфликты при отправке в Telegram API
                val typeOffset = when (type) {
                    "playtime" -> 0L // Playtime отправляется первым
                    "balance" -> 60L // Balance через 3 секунды (60 тиков)
                    else -> 0L
                }
                delayTicks += typeOffset

                // Логируем информацию о планировании
                if (conf.debugEnabled) {
                    plugin.logger.info("🕐 Планирование $type top: целевое время $timeStr, текущее время ${now.toLocalTime()} (${timezone.id})")
                    plugin.logger.info("⏱️ Задержка до выполнения: ${delaySeconds / 60} минут (${delaySeconds}s) + смещение ${typeOffset / 20}s")
                }

                // Планируем задачу
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable {
                    sendAutoTopNotification(type)

                    // Планируем следующий запуск через 24 часа
                    Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
                        sendAutoTopNotification(type)
                    }, 24 * 60 * 60 * 20L, 24 * 60 * 60 * 20L) // 24 часа в тиках

                }, delayTicks)

                plugin.logger.info("✅ Запланировано $type top уведомление на $timeStr (${timezone.id}), следующий запуск через ${delaySeconds / 60} минут (+ ${typeOffset / 20}s смещение)")

            } catch (e: Exception) {
                plugin.logger.warning("❌ Неверный формат времени в расписании: $timeStr")
            }
        }
    }

    /**
     * Отправляет автоматическое уведомление о топе
     */
    private fun sendAutoTopNotification(type: String) {
        when (type) {
            "playtime" -> sendAutoPlaytimeTop()
            "balance" -> sendAutoBalanceTop()
        }
    }

    /**
     * Отправляет автоматический топ по времени игры
     */
    private fun sendAutoPlaytimeTop() {
        try {
            // Определяем период
            val period = when (conf.playtimeTopAutoPeriod.lowercase()) {
                "1h", "1ч" -> StatsManager.StatsPeriod.HOUR
                "1d", "1д" -> StatsManager.StatsPeriod.TODAY
                "1w", "1н" -> StatsManager.StatsPeriod.WEEK
                "1m", "1м" -> StatsManager.StatsPeriod.MONTH
                else -> StatsManager.StatsPeriod.TODAY
            }

            var playtimeTop = ZTele.stats.getPlaytimeTop(period, 20) // Берем больше для фильтрации

            // Фильтруем игроков с исключенными правами
            if (conf.playtimeTopExcludeEnabled) {
                playtimeTop = playtimeTop.filter { entry ->
                    val player = Bukkit.getOfflinePlayer(entry.playerName)
                    !hasExcludedPermission(player, conf.playtimeTopExcludePermissions)
                }.take(10) // Берем топ-10 после фильтрации
            } else {
                playtimeTop = playtimeTop.take(10)
            }

            if (playtimeTop.isEmpty()) {
                return // Не отправляем пустой топ
            }

            // Формируем список топа с эмодзи и именами игроков
            val topList = buildString {
                playtimeTop.forEachIndexed { index, entry ->
                    val position = index + 1
                    val medal = when (position) {
                        1 -> "🥇"
                        2 -> "🥈"
                        3 -> "🥉"
                        4 -> "④"
                        5 -> "⓹"
                        6 -> "⓺"
                        7 -> "⓻"
                        8 -> "⓼"
                        9 -> "⓽"
                        10 -> "⓾"
                        else -> "$position."
                    }
                    append("$medal **${entry.playerName}** — **${ZTele.stats.formatPlaytime(entry.minutes)}**")
                    if (index < playtimeTop.size - 1) append("\n")
                }
            }

            // Формируем сообщение с заголовком, списком и подвалом
            val message = buildString {
                append(conf.playtimeTopAutoTitle)
                append("\n\n")
                append(topList)
                append("\n\n")
                append(conf.playtimeTopAutoFooter)
            }

            sendAutoDeleteMessage(getTargetChatId(getStatisticsChannelId()), message, conf.playtimeTopAutoDeleteSeconds)

            // Выдаем награду топ-1 игроку
            if (conf.playtimeTopRewardsEnabled && playtimeTop.isNotEmpty() && conf.playtimeTopRewardsList.isNotEmpty()) {
                val topPlayer = playtimeTop[0]

                // Выбираем случайную награду
                val randomReward = conf.playtimeTopRewardsList.random()

                // Выдаем награду
                giveRewards(topPlayer.playerName, randomReward.commands, mapOf(
                    "%player%" to topPlayer.playerName,
                    "%time%" to ZTele.stats.formatPlaytime(topPlayer.minutes)
                ))

                // Отправляем уведомление о награде
                val rewardContext = PlaceholderEngine.createCustomContext(mapOf(
                    "player" to topPlayer.playerName,
                    "time" to ZTele.stats.formatPlaytime(topPlayer.minutes),
                    "reward_name" to randomReward.name
                ))
                val rewardMessage = PlaceholderEngine.process(conf.playtimeTopRewardsNotification, rewardContext)
                sendAutoDeleteMessage(getTargetChatId(getStatisticsChannelId()), rewardMessage, conf.playtimeTopRewardsNotificationAutoDeleteSeconds)
            }

        } catch (e: Exception) {
            plugin.logger.warning("Error sending auto playtime top: ${e.message}")
        }
    }

    /**
     * Отправляет автоматический топ по балансу
     */
    private fun sendAutoBalanceTop() {
        try {
            // Используем TopManager для получения актуального топа по балансу всех игроков
            val topResult = TopManager.getBalanceTop(20) // Берем больше для фильтрации
            
            if (topResult !is TopManager.TopResult.Success) {
                plugin.logger.warning("⚠️ [sendAutoBalanceTop] Не удалось получить топ по балансу: ${(topResult as? TopManager.TopResult.Error)?.message}")
                return
            }
            
            var topBalances = topResult.entries.map { it.playerName to it.balance }

            // Фильтруем игроков с исключенными правами
            if (conf.balanceTopExcludeEnabled) {
                topBalances = topBalances.filter { entry ->
                    val player = Bukkit.getOfflinePlayer(entry.first)
                    !hasExcludedPermission(player, conf.balanceTopExcludePermissions)
                }.take(10) // Берем топ-10 после фильтрации
            } else {
                topBalances = topBalances.take(10)
            }

            if (topBalances.isEmpty()) {
                return // Не отправляем пустой топ
            }

            // Формируем список топа с эмодзи и именами игроков
            val topList = buildString {
                topBalances.forEachIndexed { index, entry ->
                    val position = index + 1
                    val medal = when (position) {
                        1 -> "🥇"
                        2 -> "🥈"
                        3 -> "🥉"
                        4 -> "④"
                        5 -> "⓹"
                        6 -> "⓺"
                        7 -> "⓻"
                        8 -> "⓼"
                        9 -> "⓽"
                        10 -> "⓾"
                        else -> "$position."
                    }
                    append("$medal `${entry.first}` — **${String.format("%.2f", entry.second)}** ⛃")
                    if (index < topBalances.size - 1) append("\n")
                }
            }

            // Формируем сообщение с заголовком, списком и подвалом
            val message = buildString {
                append(conf.balanceTopAutoTitle)
                append("\n\n")
                append(topList)
                append("\n\n")
                append(conf.balanceTopAutoFooter)
            }

            sendMarkdownMessage(getTargetChatId(getStatisticsChannelId()), message, conf.balanceTopAutoDeleteSeconds)

            // Логируем информацию о наградах для отладки
            plugin.logger.info("💰 Balance top rewards check:")
            plugin.logger.info("   - Rewards enabled: ${conf.balanceTopRewardsEnabled}")
            plugin.logger.info("   - Top balances count: ${topBalances.size}")
            plugin.logger.info("   - Rewards list size: ${conf.balanceTopRewardsList.size}")
            if (conf.balanceTopRewardsList.isNotEmpty()) {
                plugin.logger.info("   - Available rewards:")
                conf.balanceTopRewardsList.forEach { reward ->
                    plugin.logger.info("     * ${reward.name} (${reward.commands.size} commands)")
                }
            }

            // Выдаем награду топ-1 игроку
            if (conf.balanceTopRewardsEnabled && topBalances.isNotEmpty() && conf.balanceTopRewardsList.isNotEmpty()) {
                val topPlayer = topBalances[0]

                plugin.logger.info("🎁 Giving reward to top player: ${topPlayer.first}")

                // Выбираем случайную награду
                val randomReward = conf.balanceTopRewardsList.random()

                plugin.logger.info("🎲 Selected reward: ${randomReward.name}")

                // Выдаем награду
                giveRewards(topPlayer.first, randomReward.commands, mapOf(
                    "%player%" to topPlayer.first,
                    "%balance%" to String.format("%.2f", topPlayer.second)
                ))

                // Отправляем уведомление о награде
                val rewardContext = PlaceholderEngine.createCustomContext(mapOf(
                    "player" to topPlayer.first,
                    "balance" to String.format("%.2f", topPlayer.second),
                    "reward_name" to randomReward.name
                ))
                val rewardMessage = PlaceholderEngine.process(conf.balanceTopRewardsNotification, rewardContext)

                plugin.logger.info("📤 Sending reward notification: $rewardMessage")

                sendAutoDeleteMessage(getTargetChatId(getStatisticsChannelId()), rewardMessage, conf.balanceTopRewardsNotificationAutoDeleteSeconds)

                plugin.logger.info("✅ Reward notification sent successfully")
            } else {
                plugin.logger.warning("⚠️ Reward not given - one of conditions failed:")
                plugin.logger.warning("   - Rewards enabled: ${conf.balanceTopRewardsEnabled}")
                plugin.logger.warning("   - Has top players: ${topBalances.isNotEmpty()}")
                plugin.logger.warning("   - Has rewards list: ${conf.balanceTopRewardsList.isNotEmpty()}")
            }

        } catch (e: Exception) {
            plugin.logger.warning("Error sending auto balance top: ${e.message}")
        }
    }

    /**
     * Выдает награды игроку
     */
    private fun giveRewards(playerName: String, commands: List<String>, placeholders: Map<String, String>) {
        if (commands.isEmpty()) return

        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                for (command in commands) {
                    var processedCommand = command

                    // Заменяем плейсхолдеры
                    for ((placeholder, value) in placeholders) {
                        processedCommand = processedCommand.replace(placeholder, value)
                    }

                    // Выполняем команду от имени консоли
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand)
                    plugin.logger.info("Executed reward command: $processedCommand")
                }
            } catch (e: Exception) {
                plugin.logger.warning("Error executing reward commands for $playerName: ${e.message}")
            }
        })
    }

    /**
     * Проверяет, имеет ли игрок исключенные права
     */
    private fun hasExcludedPermission(player: org.bukkit.OfflinePlayer, permissions: List<String>): Boolean {
        if (permissions.isEmpty()) return false

        // Для онлайн игроков проверяем права напрямую
        if (player.isOnline) {
            val onlinePlayer = player.player
            if (onlinePlayer != null) {
                return permissions.any { permission ->
                    onlinePlayer.hasPermission(permission)
                }
            }
        }

        // Для оффлайн игроков пытаемся проверить через Vault (если доступен)
        try {
            val perms = plugin.server.servicesManager.getRegistration(net.milkbowl.vault.permission.Permission::class.java)
            if (perms != null) {
                val permission = perms.provider
                return permissions.any { perm ->
                    permission.playerHas(null, player, perm)
                }
            }
        } catch (e: Exception) {
            // Vault не доступен или ошибка, игнорируем
        }

        return false
    }

    /**
     * Форматирует время в читаемый вид
     */
    private fun formatTime(minutes: Int): String {
        return when {
            minutes == 1 -> "1 минуту"
            minutes < 5 -> "$minutes минуты"
            else -> "$minutes минут"
        }
    }

    /**
     * Парсит строку времени в минуты
     * Поддерживает форматы: 5m, 10min, 15, 1h30m
     */
    private fun parseTimeToMinutes(timeStr: String): Int? {
        if (timeStr.isBlank()) return null

        return try {
            val cleanTime = timeStr.lowercase().trim()

            when {
                // Только цифры - считаем как минуты
                cleanTime.matches(Regex("^\\d+$")) -> {
                    cleanTime.toInt()
                }

                // Формат с 'm' или 'min'
                cleanTime.matches(Regex("^\\d+m(in)?$")) -> {
                    cleanTime.replace(Regex("[^\\d]"), "").toInt()
                }

                // Формат с 'h' (часы)
                cleanTime.matches(Regex("^\\d+h$")) -> {
                    cleanTime.replace("h", "").toInt() * 60
                }

                // Комбинированный формат 1h30m
                cleanTime.matches(Regex("^\\d+h\\d+m$")) -> {
                    val parts = cleanTime.split("h", "m")
                    val hours = parts[0].toInt()
                    val minutes = parts[1].toInt()
                    hours * 60 + minutes
                }

                // Формат с 's' (секунды) - конвертируем в минуты
                cleanTime.matches(Regex("^\\d+s(ec)?$")) -> {
                    val seconds = cleanTime.replace(Regex("[^\\d]"), "").toInt()
                    maxOf(1, seconds / 60) // Минимум 1 минута
                }

                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Отправляет сообщение в основной канал
     */
    fun sendMessageToMain(message: String) {
        sendAutoDeleteMessage(getTargetChatId(conf.mainChannelId), message, 0)
    }

    /**
     * Отправляет сообщение в консольный канал
     */
    fun sendMessageToConsole(message: String) {
        sendAutoDeleteMessage(getTargetChatId(conf.consoleChannelId), message, 0)
    }

    /**
     * Проверяет, должно ли сообщение быть обработано согласно фильтрам
     */
    private fun shouldProcessMessage(message: org.telegram.telegrambots.meta.api.objects.Message): Boolean {
        // Если фильтрация отключена, пропускаем все сообщения
        if (!conf.messageFilterEnabled) {
            return true
        }

        val user = message.from
        val text = message.text
        val userId = user.id
        val isBot = user.isBot

        if (conf.debugEnabled) {
            plugin.logger.info("🔍 [MessageFilter] Проверяем сообщение от ${user.userName ?: user.firstName} ($userId)")
            plugin.logger.info("🔍 [MessageFilter] Является ботом: $isBot, Длина сообщения: ${text.length}")
        }

        // 1. Проверка на ботов
        if (conf.messageFilterBlockBots && isBot) {
            if (conf.debugEnabled) {
                plugin.logger.info("🚫 [MessageFilter] Заблокировано: сообщение от бота")
            }
            return false
        }

        // 2. Проверка белого списка пользователей
        if (conf.messageFilterWhitelistUsers.isNotEmpty() && !conf.messageFilterWhitelistUsers.contains(userId)) {
            if (conf.debugEnabled) {
                plugin.logger.info("🚫 [MessageFilter] Заблокировано: пользователь $userId не в белом списке")
            }
            return false
        }

        // 3. Проверка длины сообщения
        if (conf.messageFilterMaxLength > 0 && text.length > conf.messageFilterMaxLength) {
            if (conf.debugEnabled) {
                plugin.logger.info("🚫 [MessageFilter] Заблокировано: сообщение слишком длинное (${text.length} > ${conf.messageFilterMaxLength})")
            }
            return false
        }

        if (conf.debugEnabled) {
            plugin.logger.info("✅ [MessageFilter] Сообщение прошло все фильтры")
        }

        return true
    }

    /**
     * Отправляет сообщение с автоудалением и диагностикой ошибок
     */
    private fun sendAutoDeleteMessage(
        chatId: String,
        text: String,
        deleteAfterSeconds: Int,
        configPath: String? = null
    ) {
        if (conf.debugEnabled) {
            plugin.logger.info("[sendAutoDeleteMessage] Input chatId: '$chatId', parsed baseChatId: '${chatId.substringBefore("_")}', threadId: ${if (chatId.contains("_")) chatId.substringAfter("_") else "null"}")
        }

        if (chatId.isEmpty() || text.isEmpty()) {
            if (conf.debugEnabled) {
                plugin.logger.info("[sendAutoDeleteMessage] Skipped: chatId.isEmpty=${chatId.isEmpty()}, text.isEmpty=${text.isEmpty()}")
            }
            return
        }

        if (!connectionState.get()) {
            if (conf.debugEnabled) {
                plugin.logger.info("[sendAutoDeleteMessage] Skipped: connection is inactive")
            }
            logThrottled("SEND_AUTO_DELETE", "Cannot send message - connection is inactive", "WARNING")
            return
        }

        if (conf.debugEnabled) {
            plugin.logger.info("[sendAutoDeleteMessage] Sending to main chat ${chatId.substringBefore("_")} ${if (chatId.contains("_")) "(thread ${chatId.substringAfter("_")})" else "(no thread)"}")
        }

        try {
            val (baseChatId, threadId) = parseChatId(chatId)
            val sendMessage = SendMessage(baseChatId, convertToHtml(text))

            if (threadId != null) {
                sendMessage.messageThreadId = threadId
            }
            sendMessage.parseMode = "HTML"

            val sentMessage = execute(sendMessage)

            if (deleteAfterSeconds > 0 && sentMessage != null) {
                plugin.server.scheduler.runTaskLaterAsynchronously(plugin, Runnable {
                    try {
                        val deleteMessage = DeleteMessage(baseChatId, sentMessage.messageId)
                        execute(deleteMessage)
                    } catch (e: Exception) {
                        // Игнорируем ошибки удаления
                    }
                }, (deleteAfterSeconds * 20L))
            }

        } catch (e: org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException) {
            org.zoobastiks.ztelegram.utils.TelegramErrorDiagnostics.diagnoseError(
                exception = e,
                context = "SEND_AUTO_DELETE_MESSAGE",
                message = text,
                configPath = configPath
            )
        } catch (e: org.telegram.telegrambots.meta.exceptions.TelegramApiException) {
            org.zoobastiks.ztelegram.utils.TelegramErrorDiagnostics.diagnoseError(
                exception = e,
                context = "SEND_AUTO_DELETE_MESSAGE",
                message = text,
                configPath = configPath
            )
        } catch (e: Exception) {
            handleConnectionError(e, "SEND_AUTO_DELETE_UNEXPECTED")
        }
    }
    
    // ========== МЕТОДЫ ДЛЯ КНИГ С КЛАВИАТУРОЙ ==========
    
    private fun createInlineKeyboardJson(
        prevCallbackData: String,
        nextCallbackData: String,
        isFirstPage: Boolean,
        isLastPage: Boolean
    ): String {
        if (isFirstPage) {
            return JSONObject().apply {
                put("inline_keyboard", listOf(
                    listOf(
                        JSONObject().apply {
                            put("text", "Next ➡️")
                            put("callback_data", nextCallbackData)
                        }
                    )
                ))
            }.toString()
        } else if (isLastPage) {
            return JSONObject().apply {
                put("inline_keyboard", listOf(
                    listOf(
                        JSONObject().apply {
                            put("text", "⬅️ Back")
                            put("callback_data", prevCallbackData)
                        }
                    )
                ))
            }.toString()
        }
        return JSONObject().apply {
            put("inline_keyboard", listOf(
                listOf(
                    JSONObject().apply {
                        put("text", "⬅️ Back")
                        put("callback_data", prevCallbackData)
                    },
                    JSONObject().apply {
                        put("text", "Next ➡️")
                        put("callback_data", nextCallbackData)
                    }
                )
            ))
        }.toString()
    }
    
    fun sendImageWithKeyboard(chatId: Long, imageIndex: Int, imageDirectory: File, caption: String?) {
        val imageFile = File(imageDirectory, "page$imageIndex.png")
        val totalPages = imageDirectory.listFiles { file -> file.name.endsWith(".png") }?.size ?: 1
        val isLastPage = imageIndex == totalPages
        
        val imageBytes = imageFile.readBytes()
        val bookHash = imageDirectory.absolutePath.split(File.separator).last()
        
        var keyboardJson = ""
        if (!isLastPage) {
            keyboardJson = createInlineKeyboardJson("prev_$imageIndex-$bookHash", "next_$imageIndex-$bookHash", true, isLastPage)
        }
        
        sendPhotoWithKeyboard(chatId.toString(), imageBytes, caption, keyboardJson)
    }
    
    fun editImageWithKeyboard(chatId: Long, messageId: Int, imageIndex: Int, imageDirectory: File, hash: String) {
        val imageFile = File(imageDirectory, "page$imageIndex.png")
        val totalPages = imageDirectory.listFiles { file -> file.name.endsWith(".png") }?.size ?: 1
        
        val isFirstPage = imageIndex == 1
        val isLastPage = imageIndex == totalPages
        
        val imageBytes = imageFile.readBytes()
        val keyboardJson = createInlineKeyboardJson("prev_$imageIndex-$hash", "next_$imageIndex-$hash", isFirstPage, isLastPage)
        
        editMessagePhoto(chatId.toString(), messageId, imageBytes, keyboardJson)
    }
    
    private fun sendPhotoWithKeyboard(chatId: String, imageBytes: ByteArray, caption: String?, replyMarkupJson: String) {
        if (!connectionState.get()) return
        
        try {
            val (baseChatId, threadId) = parseChatId(chatId)
            val sendPhoto = SendPhoto()
            sendPhoto.chatId = baseChatId
            sendPhoto.photo = InputFile(ByteArrayInputStream(imageBytes), "image.png")
            sendPhoto.parseMode = "HTML"
            
            if (threadId != null) {
                sendPhoto.messageThreadId = threadId
            }
            if (caption != null) {
                sendPhoto.caption = caption
            }
            if (replyMarkupJson.isNotEmpty()) {
                val replyMarkup = InlineKeyboardMarkup()
                val jsonArray = JSONObject(replyMarkupJson).getJSONArray("inline_keyboard")
                val keyboard = mutableListOf<List<InlineKeyboardButton>>()
                
                for (i in 0 until jsonArray.length()) {
                    val rowArray = jsonArray.getJSONArray(i)
                    val row = mutableListOf<InlineKeyboardButton>()
                    for (j in 0 until rowArray.length()) {
                        val buttonData = rowArray.getJSONObject(j)
                        val button = InlineKeyboardButton()
                        button.text = buttonData.getString("text")
                        button.callbackData = buttonData.getString("callback_data")
                        row.add(button)
                    }
                    keyboard.add(row)
                }
                replyMarkup.keyboard = keyboard
                sendPhoto.replyMarkup = replyMarkup
            }
            
            execute(sendPhoto)
        } catch (e: Exception) {
            handleConnectionError(e, "SEND_PHOTO_KEYBOARD")
        }
    }
    
    private fun editMessagePhoto(chatId: String, messageId: Int, imageBytes: ByteArray, replyMarkupJson: String) {
        if (!connectionState.get()) return
        
        try {
            val (baseChatId, _) = parseChatId(chatId)
            
            // Удаляем старое сообщение и отправляем новое с клавиатурой
            deleteMessage(chatId, messageId)
            sendPhotoWithKeyboard(chatId, imageBytes, null, replyMarkupJson)
            
        } catch (e: Exception) {
            handleConnectionError(e, "EDIT_MESSAGE_PHOTO")
        }
    }
    
    // ========== ОБРАБОТЧИК НАЖАТИЙ КНОПОК ==========
    
    fun handleBookCallback(callbackQuery: CallbackQuery): Boolean {
        val data = callbackQuery.data ?: return false
        val message = callbackQuery.message ?: return false
        val chatId = message.chatId
        val messageId = message.messageId
        
        if (data.startsWith("prev_") || data.startsWith("next_")) {
            val parts = data.split("_")
            if (parts.size >= 3) {
                val currentIndex = parts[1].toIntOrNull() ?: return false
                val hash = parts[2]
                val bookFolder = File(plugin.dataFolder, "inv/books/$hash")
                
                val newIndex = if (data.startsWith("next_")) currentIndex + 1 else currentIndex - 1
                
                editImageWithKeyboard(chatId, messageId, newIndex, bookFolder, hash)
                answerCallbackQuery(callbackQuery.id)
                return true
            }
        }
        return false
    }
}
