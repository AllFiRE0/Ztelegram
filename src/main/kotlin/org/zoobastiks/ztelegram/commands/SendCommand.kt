package org.zoobastiks.ztelegram.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.zoobastiks.ztelegram.ZTele

class SendCommand(private val plugin: ZTele) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!ZTele.conf.sendCommandEnabled) {
            sender.sendMessage("§cКоманда send отключена")
            return true
        }
        
        if (!sender.hasPermission(ZTele.conf.sendCommandPermission)) {
            sender.sendMessage("§cУ вас нет прав для использования этой команды")
            return true
        }
        
        if (args.size < 3) {
            sender.sendMessage(ZTele.conf.sendCommandUsage)
            return false
        }
        
        val format = args[0]
        val chatName = args[1]
        val message = args.drop(2).joinToString(" ")
        
        val chat = ZTele.chatManager.getChat(chatName)
        if (chat == null) {
            sender.sendMessage(ZTele.conf.sendCommandChatNotFound.replace("%chat%", chatName))
            return true
        }
        
        // Обработка формата
        val formattedMessage = when (format.lowercase()) {
            "plain" -> message
            "mm" -> message // MiniMessage уже в формате
            "html" -> message // HTML как есть
            "json" -> {
                try {
                    val component = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(message)
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component)
                } catch (e: Exception) {
                    sender.sendMessage("§cОшибка парсинга JSON: ${e.message}")
                    return true
                }
            }
            else -> {
                sender.sendMessage(ZTele.conf.sendCommandInvalidFormat.replace("%format%", format))
                return true
            }
        }
        
        ZTele.chatManager.sendToTelegram(chat, "Console", formattedMessage)
        sender.sendMessage(ZTele.conf.sendCommandSuccess.replace("%chat%", chatName))
        return true
    }
}