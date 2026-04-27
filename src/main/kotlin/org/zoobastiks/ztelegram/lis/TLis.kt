package org.zoobastiks.ztelegram.lis

import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.Material
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import java.awt.Color
import org.zoobastiks.ztelegram.renderer.AdvancementRenderer
import org.zoobastiks.ztelegram.renderer.BookRenderer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.zoobastiks.ztelegram.ZTele
import org.zoobastiks.ztelegram.bot.TBot
import org.bukkit.Bukkit

class TLis(private val plugin: ZTele) : Listener {
    private val bot: TBot
        get() = ZTele.bot

    // Флаг для отслеживания обработанных сообщений чата
    private val processedMessages = mutableMapOf<String, Long>()

    // Время хранения кэша сообщений (в миллисекундах)
    private val MESSAGE_CACHE_EXPIRY = 1000L // 1 секунда

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val playerName = event.player.name
        val playerUuid = event.player.uniqueId

        // Записываем статистику входа игрока
        ZTele.stats.recordPlayerJoin(playerUuid, playerName)

        bot.sendPlayerJoinMessage(playerName)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val playerName = event.player.name
        val playerUuid = event.player.uniqueId

        // Записываем статистику выхода игрока (для подсчета времени сессии)
        ZTele.stats.recordPlayerQuit(playerUuid, playerName)

        bot.sendPlayerQuitMessage(playerName)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity

        // Получаем оригинальное английское сообщение о смерти через новый API (Paper 1.19+)
        val englishDeathMessage = try {
            val deathComponent = event.deathMessage()
            if (deathComponent != null) {
                PlainTextComponentSerializer.plainText().serialize(deathComponent)
            } else {
                "died" // Запасной вариант
            }
        } catch (e: Exception) {
            plugin.logger.warning("Failed to get death message component: ${e.message}")
            "died" // Запасной вариант
        }

        // Определяем, какое сообщение отправлять (английское или русское)
        val finalDeathMessage = if (ZTele.conf.chatPlayerDeathUseRussianMessages) {
            // Получаем убийцу (если есть)
            val killer = player.killer ?: event.entity.lastDamageCause?.let { damageEvent ->
                if (damageEvent is org.bukkit.event.entity.EntityDamageByEntityEvent) {
                    damageEvent.damager
                } else {
                    null
                }
            }

            // Получаем причину смерти
            val damageCause = event.entity.lastDamageCause?.cause

            // Переводим сообщение на русский с помощью DeathMessageManager
            val russianMessage = ZTele.deathMessages.getDeathMessage(
                player = player,
                deathMessage = englishDeathMessage,
                killer = killer,
                cause = damageCause
            )

            // Если включен режим отладки, показываем оба сообщения
            if (ZTele.conf.chatPlayerDeathDebugMessages) {
                plugin.logger.info("╔════════════════════════════════════════════════════╗")
                plugin.logger.info("║ [Death Message Debug]                              ║")
                plugin.logger.info("╠════════════════════════════════════════════════════╣")
                plugin.logger.info("║ Player: ${player.name}")
                plugin.logger.info("║ Killer: ${killer?.name ?: "none"}")
                plugin.logger.info("║ Cause: ${damageCause?.name ?: "none"}")
                plugin.logger.info("║ Original (EN): $englishDeathMessage")
                plugin.logger.info("║ Translated (RU): $russianMessage")
                plugin.logger.info("╚════════════════════════════════════════════════════╝")
            }

            russianMessage
        } else {
            // Используем оригинальное английское сообщение
            englishDeathMessage
        }

        // Отправляем сообщение о смерти в Telegram
        bot.sendPlayerDeathMessage(player.name, finalDeathMessage)
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerAdvancementDone(event: PlayerAdvancementDoneEvent) {
        try {
            val display = event.advancement.display ?: return
            if (!display.doesAnnounceToChat()) return

            val advancementName = PlainTextComponentSerializer.plainText().serialize(event.advancement.displayName())
            val username = event.player.name
            val frameType = display.frame().name.lowercase()
            val description = PlainTextComponentSerializer.plainText().serialize(display.description())
        
            val message = when (frameType) {
                "goal" -> "🏆 **Цель достигнута!** _${advancementName}_"
                "challenge" -> "🔥 **Испытание завершено!** _${advancementName}_"
                else -> "✨ **Новое достижение!** _${advancementName}_"
            }
    
            val item = display.icon()
            val textColor = Color(display.frame().color().asRGB())

            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                val renderer = AdvancementRenderer()
                val imageBytes = renderer.renderAdvancement(advancementName, frameType, item, textColor)
                plugin.server.scheduler.runTask(plugin, Runnable {
                    ZTele.bot.sendPhoto(ZTele.conf.mainChannelId, imageBytes, message)
                })
            })
        } catch (e: Exception) {
            plugin.logger.warning("Failed to render advancement: ${e.message}")
        }
    }
    
    /**
     * Обработчик нового API чата PaperMC
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAsyncChat(event: AsyncChatEvent) {
        try {
            val playerName = event.player.name
            val message = PlainTextComponentSerializer.plainText().serialize(event.message())

            // РЕНДЕРИНГ [item], [inv], [ender]
            if (message.equals("[item]", true) || message.equals("[inv]", true) || message.equals("[ender]", true)) {
                bot.handleRendering(playerName, message)
                return
            }
        
            // РЕНДЕРИНГ КНИГИ
            // РЕНДЕРИНГ КНИГИ
            if (message.equals("[item]", true)) {
                val player = Bukkit.getPlayerExact(playerName)
                if (player != null) {
                    val item = player.inventory.itemInMainHand
                    if (item.type == Material.WRITTEN_BOOK || item.type == Material.WRITABLE_BOOK) {
                        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                            try {
                                val renderer = BookRenderer(plugin)
                                val (bookDirectory, caption) = renderer.renderBookToFile(item)
                                if (bookDirectory != null && bookDirectory.exists()) {
                                    plugin.server.scheduler.runTask(plugin, Runnable {
                                        val chatId = ZTele.conf.mainChannelId
                                        bot.sendImageWithKeyboard(
                                            chatId.toLong(),
                                            imageIndex = 1,
                                            imageDirectory = bookDirectory,
                                            caption = caption
                                        )
                                    })
                                }
                            } catch (e: Exception) {
                                plugin.logger.warning("Failed to render book: ${e.message}")
                            }
                        })
                        return
                    }
                }
            }

            // Проверяем, не обрабатывали ли мы уже такое сообщение недавно
            if (!hasRecentlySentMessage(playerName, message)) {
                bot.sendPlayerChatMessage(playerName, message)
                markMessageAsProcessed(playerName, message)
                plugin.logger.info("Message sent to Telegram from AsyncChatEvent: $playerName - $message")
            } else {
                plugin.logger.info("Skipping duplicate message from AsyncChatEvent: $playerName - $message")
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error processing AsyncChatEvent: ${e.message}")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val playerName = event.player.name
        val command = event.message.substring(1) // Remove the leading '/'

        // Log command to Telegram
        bot.sendPlayerCommandMessage(playerName, command)
    }

    /**
     * Проверяет, было ли недавно отправлено аналогичное сообщение
     */
    private fun hasRecentlySentMessage(playerName: String, message: String): Boolean {
        val key = "$playerName:$message"
        val currentTime = System.currentTimeMillis()

        // Очищаем старые записи
        val expiredKeys = processedMessages.filter { currentTime - it.value > MESSAGE_CACHE_EXPIRY }.keys
        expiredKeys.forEach { processedMessages.remove(it) }

        return processedMessages.containsKey(key)
    }

    /**
     * Отмечает сообщение как обработанное, чтобы избежать дублирования
     */
    private fun markMessageAsProcessed(playerName: String, message: String) {
        val key = "$playerName:$message"
        processedMessages[key] = System.currentTimeMillis()
    }
}
