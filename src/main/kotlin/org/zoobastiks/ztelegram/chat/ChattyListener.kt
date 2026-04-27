package org.zoobastiks.ztelegram.chat

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.zoobastiks.ztelegram.ZTele
import org.zoobastiks.ztelegram.renderer.*
import ru.brikster.chatty.api.event.ChattyMessageEvent

class ChattyListener(private val plugin: ZTele) : Listener {
    
    @EventHandler
    fun onChattyMessage(e: ChattyMessageEvent) {
        val player = e.sender ?: return
        val playerName = player.name
        val message = e.plainMessage
        val chatId = e.chat.id
        
        val chat = ZTele.chatManager.getChat(chatId)
        if (chat == null || !chat.enabled) return
        
        // РЕНДЕРИНГ
        when {
            message.equals("[item]", true) && !isBook(player) -> {
                handleItemRendering(playerName, player.inventory.itemInMainHand, chat)
                return
            }
            message.equals("[item]", true) && isBook(player) -> {
                handleBookRendering(playerName, player.inventory.itemInMainHand, chat)
                return
            }
            message.equals("[inv]", true) -> {
                handleInventoryRendering(playerName, player, chat)
                return
            }
            message.equals("[ender]", true) -> {
                handleEnderChestRendering(playerName, player, chat)
                return
            }
        }
        
        // Обычное сообщение
        ZTele.chatManager.sendToTelegram(chat, playerName, message)
    }
    
    private fun isBook(player: org.bukkit.entity.Player) =
        player.inventory.itemInMainHand.type.let { 
            it == Material.WRITTEN_BOOK || it == Material.WRITABLE_BOOK 
        }
    
    private fun handleItemRendering(playerName: String, item: ItemStack, chat: ChatConfig) {
        if (item.type == Material.AIR) return
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val renderer = ItemRenderer()
            val (imageBytes, itemName) = renderer.renderItemToFile(item)
            plugin.server.scheduler.runTask(plugin, Runnable {
                ZTele.bot.sendPhoto(getChatId(chat), imageBytes, "$playerName: [$itemName]")
            })
        })
    }
    
    private fun handleBookRendering(playerName: String, item: ItemStack, chat: ChatConfig) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val renderer = BookRenderer(plugin)
            val (bookDirectory, caption) = renderer.renderBookToFile(item)
            if (bookDirectory != null) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    ZTele.bot.sendImageWithKeyboard(chat.chatId, 1, bookDirectory, caption)
                })
            }
        })
    }
    
    private fun handleInventoryRendering(playerName: String, player: org.bukkit.entity.Player, chat: ChatConfig) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val renderer = InventoryRenderer()
            val imageBytes = renderer.renderInventoryToFile(player.inventory)
            plugin.server.scheduler.runTask(plugin, Runnable {
                ZTele.bot.sendPhoto(getChatId(chat), imageBytes, "$playerName: Инвентарь")
            })
        })
    }
    
    private fun handleEnderChestRendering(playerName: String, player: org.bukkit.entity.Player, chat: ChatConfig) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val renderer = EnderChestRenderer()
            val imageBytes = renderer.renderEnderChestToFile(player.enderChest)
            plugin.server.scheduler.runTask(plugin, Runnable {
                ZTele.bot.sendPhoto(getChatId(chat), imageBytes, "$playerName: Эндер-сундук")
            })
        })
    }
    
    private fun getChatId(chat: ChatConfig) = if (chat.topicId > 0) {
        "${chat.chatId}_${chat.topicId}"
    } else {
        chat.chatId.toString()
    }
}
