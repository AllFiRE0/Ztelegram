package org.zoobastiks.ztelegram.mgr

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import org.zoobastiks.ztelegram.ZTele
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class SchedulerManager(private val plugin: ZTele) {
    
    private val activeTasks = ConcurrentHashMap<String, BukkitTask>()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    
    fun start() {
        if (!ZTele.conf.schedulerEnabled) {
            if (ZTele.conf.debugEnabled) {
                plugin.logger.info("⏰ Планировщик команд отключен в конфигурации")
            }
            return
        }
        
        if (ZTele.conf.debugEnabled) {
            plugin.logger.info("⏰ Запуск планировщика автоматических команд...")
        }
        scheduleAllTasks()
        if (ZTele.conf.debugEnabled) {
            plugin.logger.info("✅ Планировщик команд запущен")
        }
    }
    
    fun stop() {
        if (ZTele.conf.debugEnabled) {
            plugin.logger.info("⏰ Остановка планировщика команд...")
        }
        activeTasks.values.forEach { task ->
            task.cancel()
        }
        activeTasks.clear()
        if (ZTele.conf.debugEnabled) {
            plugin.logger.info("✅ Планировщик команд остановлен")
        }
    }
    
    fun reload() {
        stop()
        start()
    }
    
    private fun scheduleAllTasks() {
        val dailyTasks = ZTele.conf.schedulerDailyTasks
        
        for ((taskName, taskConfig) in dailyTasks) {
            if (!taskConfig.enabled) {
                if (ZTele.conf.debugEnabled) {
                    plugin.logger.info("⏭️ Задача '$taskName' отключена, пропускаем")
                }
                continue
            }
            
            try {
                scheduleTask(taskName, taskConfig)
                if (ZTele.conf.debugEnabled) {
                    plugin.logger.info("✅ Задача '$taskName' запланирована на ${taskConfig.time}")
                }
            } catch (e: Exception) {
                plugin.logger.severe("❌ Ошибка планирования задачи '$taskName': ${e.message}")
                plugin.logger.severe("💡 Проверьте формат времени в config.yml (должно быть HH:MM, например '06:00', а не '6:00')")
            }
        }
    }
    
    private fun scheduleTask(taskName: String, taskConfig: SchedulerTaskConfig) {
        val normalizedTime = if (taskConfig.time.length == 4 && taskConfig.time[1] == ':') {
            "0${taskConfig.time}"
        } else {
            taskConfig.time
        }
        
        val timezone = try {
            ZoneId.of(ZTele.conf.schedulerTimezone)
        } catch (e: Exception) {
            plugin.logger.warning("⚠️ Неверный часовой пояс '${ZTele.conf.schedulerTimezone}', используется UTC")
            ZoneId.of("UTC")
        }
        
        val targetTime = LocalTime.parse(normalizedTime, timeFormatter)
        val now = ZonedDateTime.now(timezone)
        val currentTime = now.toLocalTime()
        
        var secondsUntilExecution = targetTime.toSecondOfDay() - currentTime.toSecondOfDay()
        
        if (secondsUntilExecution <= 0) {
            secondsUntilExecution += 24 * 60 * 60
        }
        
        val delayTicks = secondsUntilExecution * 20L
        val periodTicks = 24 * 60 * 60 * 20L
        
        if (ZTele.conf.debugEnabled) {
            plugin.logger.info("🕐 Задача '$taskName': целевое время ${taskConfig.time}, текущее время $currentTime (${timezone.id})")
            plugin.logger.info("⏱️ Задержка до выполнения: ${secondsUntilExecution / 60} минут")
        }
        
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            executeTask(taskName, taskConfig)
        }, delayTicks, periodTicks)
        
        activeTasks[taskName] = task
    }
    
    private fun executeTask(taskName: String, taskConfig: SchedulerTaskConfig) {
        try {
            if (ZTele.conf.debugEnabled) {
                plugin.logger.info("⚡ Выполнение задачи: $taskName")
            }
            
            for (command in taskConfig.commands) {
                try {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        val result = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                        if (ZTele.conf.debugEnabled) {
                            plugin.logger.info("📋 Выполнена команда: $command (результат: $result)")
                        }
                    })
                } catch (e: Exception) {
                    plugin.logger.severe("❌ Ошибка выполнения команды '$command' в задаче '$taskName': ${e.message}")
                }
            }
            
            if (ZTele.conf.schedulerLoggingTelegram && ZTele.conf.consoleChannelId.isNotEmpty()) {
                sendTelegramNotification(taskName, taskConfig)
            }
            
            if (ZTele.conf.debugEnabled) {
                plugin.logger.info("✅ Задача '$taskName' выполнена успешно")
            }
            
        } catch (e: Exception) {
            plugin.logger.severe("❌ Критическая ошибка при выполнении задачи '$taskName': ${e.message}")
        }
    }
    
    private fun sendTelegramNotification(taskName: String, taskConfig: SchedulerTaskConfig) {
        if (ZTele.conf.consoleChannelId.isEmpty()) return
        try {
            val timezone = try {
                ZoneId.of(ZTele.conf.schedulerTimezone)
            } catch (e: Exception) {
                ZoneId.of("UTC")
            }
            
            val currentTime = ZonedDateTime.now(timezone).toLocalTime()
            
            val message = buildString {
                append("⚡ **Автоматическая задача выполнена**\n")
                append("📋 Название: `$taskName`\n")
                append("⏰ Запланировано: `${taskConfig.time}`\n")
                append("📝 Команд выполнено: `${taskConfig.commands.size}`\n")
                append("🕒 Время выполнения: `${currentTime.format(timeFormatter)}` (${timezone.id})")
            }
            
            ZTele.bot.sendMessageToConsole(message)
        } catch (e: Exception) {
            if (ZTele.conf.debugEnabled) {
                plugin.logger.warning("⚠️ Не удалось отправить уведомление в Telegram: ${e.message}")
            }
        }
    }
    
    fun getActiveTasks(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val dailyTasks = ZTele.conf.schedulerDailyTasks
        
        for ((taskName, taskConfig) in dailyTasks) {
            if (taskConfig.enabled && activeTasks.containsKey(taskName)) {
                result[taskName] = "${taskConfig.time} (${taskConfig.commands.size} команд)"
            }
        }
        
        return result
    }
    
    fun scheduleRestart(delayMinutes: Int, initiator: String) {
        if (ZTele.conf.debugEnabled) {
            plugin.logger.info("⏰ Планирование рестарта через $delayMinutes минут от $initiator")
        }
        
        val message = "🔄 **Рестарт сервера запланирован!**\n" +
                     "⏰ Сервер будет перезагружен через **$delayMinutes минут**\n" +
                     "👤 Инициатор: $initiator"
        
        ZTele.bot.sendAutoDeleteMessage(ZTele.conf.mainChannelId, message, 0)
        
        val delayTicks = delayMinutes * 60 * 20L
        
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "restart")
            })
        }, delayTicks)
    }
    
    fun cancelScheduledRestart(): Boolean {
        return false
    }
    
    data class SchedulerTaskConfig(
        val time: String,
        val commands: List<String>,
        val enabled: Boolean
    )
}
