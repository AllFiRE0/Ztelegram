package org.zoobastiks.ztelegram.renderer

import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class InventoryRenderer {
    private val slotSize = 32
    private val padding = 4
    private val borderSize = 16
    private val bottomPadding = 8

    fun renderInventory(inventory: Inventory): ByteArray {
        val columns = 9
        val rows = 5
        val imageWidth = columns * (slotSize + padding) + borderSize * 2
        val imageHeight = rows * (slotSize + padding) + borderSize * 2 + bottomPadding
        val image = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()

        // Фон
        g.color = Color(139, 139, 139)
        g.fillRect(0, 0, imageWidth, imageHeight)

        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val index = when (row) {
                    0 -> 36 + col
                    1 -> 9 + col
                    2 -> 18 + col
                    3 -> 27 + col
                    4 -> col
                    else -> col
                }
                if (row == 0 && col >= 5) continue

                var y = row * (slotSize + padding) + borderSize
                if (row == 4) y += bottomPadding
                val x = col * (slotSize + padding) + borderSize

                val item = inventory.getItem(index)
                if (item != null && item.type != Material.AIR) {
                    drawItem(g, item, x, y)
                }
            }
        }

        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun drawItem(g: Graphics2D, item: ItemStack, x: Int, y: Int) {
        val name = item.type.name.lowercase()
        val texture = when {
            name.contains("potion") -> loadPotionTexture(item, this.javaClass)
            name.contains("map") -> loadMapTexture(this.javaClass)
            else -> loadItemTexture(name, this.javaClass)
        }
        if (texture != null) {
            g.drawImage(texture, x, y, slotSize, slotSize, null)
        } else {
            g.color = Color.GRAY
            g.fillRect(x, y, slotSize, slotSize)
        }

        // Количество
        if (item.amount > 1) {
            g.color = Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 16)
            val count = item.amount.toString()
            val w = g.fontMetrics.stringWidth(count)
            g.drawString(count, x + slotSize - w + 2, y + slotSize + 10)
        }
    }
}