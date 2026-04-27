package org.zoobastiks.ztelegram.checkin

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.zoobastiks.ztelegram.ZTele

class CheckinCommand(private val plugin: ZTele) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // Сброс очков чекина для администраторов
        if (args.isNotEmpty() && args[0].equals("reset", true)) {
            if (!sender.hasPermission("ztelegram.admin")) {
                sender.sendMessage("${ZTele.conf.pluginPrefix} §cУ вас нет прав для сброса очков чекина!")
                return true
            }
            
            if (args.size < 2) {
                sender.sendMessage("${ZTele.conf.pluginPrefix} §cИспользование: /checkin reset <никнейм>")
                return true
            }
            
            val targetName = args[1]
            val connection = ZTele.checkinManager.getConnection()
            val deleted = connection?.prepareStatement("DELETE FROM checkins WHERE player_name = ?")?.use { stmt ->
                stmt.setString(1, targetName.lowercase())
                stmt.executeUpdate()
            } ?: 0
            
            if (deleted > 0) {
                val message = ZTele.conf.checkinResetSuccess.replace("%player%", targetName)
                sender.sendMessage(message)
            } else {
                val message = ZTele.conf.checkinResetNotFound.replace("%player%", targetName)
                sender.sendMessage(message)
            }
            return true
        }
        
        // Обычная логика чекина
        if (sender !is Player) {
            sender.sendMessage("${ZTele.conf.pluginPrefix} §cТолько игроки могут использовать эту команду!")
            return true
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val result = ZTele.checkinManager.checkin(sender.name)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                sender.sendMessage(result.message)
            })
        })
        return true
    }
}
