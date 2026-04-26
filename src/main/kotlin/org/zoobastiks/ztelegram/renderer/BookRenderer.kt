package org.zoobastiks.ztelegram.renderer

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

class BookRenderer {
    private val marginLeft = 40
    private val marginTop = 60
    private val pageWidth = 350
    private val pageHeight = 432

    fun renderBook(book: ItemStack, dataFolder: File): Pair<File?, String?> {
        val meta = book.itemMeta as? BookMeta ?: return Pair(null, null)
        val serializer = PlainTextComponentSerializer.plainText()
        val pages = meta.pages().map { serializer.serialize(it) }
        val title = meta.title ?: "Untitled"
        val author = meta.author ?: "Unknown"

        val hash = generateHash(title, author, pages)
        val outputFolder = File(dataFolder, "inv/books/$hash")
        outputFolder.mkdirs()

        for ((index, page) in pages.withIndex()) {
            val image = BufferedImage(pageWidth, pageHeight, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()

            // Фон
            g.color = Color(255, 255, 255)
            g.fillRect(0, 0, pageWidth, pageHeight)

            // Текст
            g.color = Color.BLACK
            g.font = Font("SansSerif", Font.PLAIN, 14)
            val fm = g.fontMetrics
            val maxWidth = pageWidth - marginLeft * 2
            val lineHeight = fm.height

            val pageNum = "Стр. ${index + 1}/${pages.size}"
            g.drawString(pageNum, pageWidth - fm.stringWidth(pageNum) - 30, marginTop)

            var y = marginTop + lineHeight + 10
            for (line in page.split("\n")) {
                for (word in line.split(" ").flatMap { if (fm.stringWidth(it) > maxWidth) it.chunked(19) else listOf(it) }) {
                    val testLine = if (y == lineHeight) word else "$word "
                    if (fm.stringWidth(testLine) > maxWidth) {
                        y += lineHeight
                    }
                }
                g.drawString(line, marginLeft, y)
                y += lineHeight
            }

            g.dispose()
            val file = File(outputFolder, "page${index + 1}.png")
            ImageIO.write(image, "png", file)
        }

        return Pair(outputFolder, "«$title» — $author")
    }

    private fun generateHash(title: String, author: String, pages: List<String>): String {
        val data = "$title:$author:${pages.joinToString()}"
        return MessageDigest.getInstance("MD5").digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}