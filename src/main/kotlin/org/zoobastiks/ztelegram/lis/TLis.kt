package org.zoobastiks.ztelegram.lis

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.zoobastiks.ztelegram.ZTele
import org.zoobastiks.ztelegram.bot.TBot
import org.zoobastiks.ztelegram.renderer.AdvancementRenderer
import java.awt.Color

class TLis(private val plugin: ZTele) : Listener {
    private val bot: TBot
        get() = ZTele.bot

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        ZTele.stats.recordPlayerJoin(event.player.uniqueId, event.player.name)
        bot.sendPlayerJoinMessage(event.player.name)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        ZTele.stats.recordPlayerQuit(event.player.uniqueId, event.player.name)
        bot.sendPlayerQuitMessage(event.player.name)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val englishDeathMessage = try {
            event.deathMessage()?.let { PlainTextComponentSerializer.plainText().serialize(it) } ?: "died"
        } catch (e: Exception) {
            "died"
        }

        val finalDeathMessage = if (ZTele.conf.chatPlayerDeathUseRussianMessages) {
            val killer = player.killer ?: event.entity.lastDamageCause?.let { damageEvent ->
                if (damageEvent is org.bukkit.event.entity.EntityDamageByEntityEvent) damageEvent.damager else null
            }
            ZTele.deathMessages.getDeathMessage(player, englishDeathMessage, killer, event.entity.lastDamageCause?.cause)
        } else {
            englishDeathMessage
        }

        bot.sendPlayerDeathMessage(player.name, finalDeathMessage)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerAdvancementDone(event: PlayerAdvancementDoneEvent) {
        try {
            if (!ZTele.conf.advancementsEnabled) return
        
            val display = event.advancement.display ?: return
            if (!display.doesAnnounceToChat()) return

            val frameType = display.frame().name.lowercase()
        
            // Проверяем, включён ли этот тип достижений
            val (enabled, format) = when (frameType) {
                "goal" -> ZTele.conf.advancementsGoalEnabled to ZTele.conf.advancementsGoalFormat
                "challenge" -> ZTele.conf.advancementsChallengeEnabled to ZTele.conf.advancementsChallengeFormat
                else -> ZTele.conf.advancementsTaskEnabled to ZTele.conf.advancementsTaskFormat
            }
            if (!enabled) return

            val advancementName = PlainTextComponentSerializer.plainText().serialize(event.advancement.displayName())
            val username = event.player.name
            val description = PlainTextComponentSerializer.plainText().serialize(display.description())
        
            val message = format
                .replace("%player%", username)
                .replace("%advancement%", advancementName)
                .replace("%description%", description)

            if (ZTele.conf.advancementsSendImage) {
                val item = display.icon()
                val textColor = Color.decode(display.frame().color().asHexString())

                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    val renderer = AdvancementRenderer()
                    val imageBytes = renderer.renderAdvancement(advancementName, frameType, item, textColor)
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        ZTele.bot.sendPhoto(ZTele.conf.mainChannelId, imageBytes, message)
                    })
                })
            } else {
                ZTele.bot.sendMessageToMain(message)
            }
        } catch (e: Exception) {
            plugin.logger.warning("Failed to render advancement: ${e.message}")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        bot.sendPlayerCommandMessage(event.player.name, event.message.substring(1))
    }
}
