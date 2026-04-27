package org.zoobastiks.ztelegram.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

object ChatFormatter {
    private val miniMessage = MiniMessage.miniMessage()
    private val plainSerializer = PlainTextComponentSerializer.plainText()

    fun formatMinecraftMessage(format: String, sender: String, text: String): Component {
        val result = format
            .replace("<sender>", sender)
            .replace("<text>", text)
        return try {
            miniMessage.deserialize(result)
        } catch (e: Exception) {
            Component.text(result)
        }
    }

    fun formatTelegramMessage(format: String, username: String, text: String): String {
        return format
            .replace("<username>", username)
            .replace("<text>", text)
    }

    fun formatMinecraftMessageFromTG(format: String, username: String, message: String): Component {
        val result = format
            .replace("<sender>", username)   // ← ИСПРАВЛЕНО
            .replace("<text>", message)
        return try {
            miniMessage.deserialize(result)
        } catch (e: Exception) {
            Component.text("$username: $message")
        }
    }
}
