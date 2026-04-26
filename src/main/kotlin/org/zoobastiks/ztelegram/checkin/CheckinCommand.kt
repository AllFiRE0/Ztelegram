package org.zoobastiks.ztelegram.checkin

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.zoobastiks.ztelegram.ZTele

class CheckinCommand(private val plugin: ZTele) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cТолько игроки могут использовать эту команду!")
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