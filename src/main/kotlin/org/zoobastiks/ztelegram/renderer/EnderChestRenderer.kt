package org.zoobastiks.ztelegram.renderer

import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class EnderChestRenderer {
    private val slotSize = 32
    private val padding = 4
    private val borderSize = 16
    
    fun renderEnderChest(inventory: Inventory): ByteArray {
        val columns = 9
        val rows = 3
        val imageWidth = columns * (slotSize + padding) + borderSize * 2
        val imageHeight = rows * (slotSize + padding) + borderSize * 2
        val image = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        
        // Фон
        g.color = Color(139, 139, 139)
        g.fillRect(0, 0, imageWidth, imageHeight)
        
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val index = row * 9 + col
                val x = col * (slotSize + padding) + borderSize
                val y = row * (slotSize + padding) + borderSize
                
                val item = inventory.getItem(index)
                if (item != null && item.type != Material.AIR) {
                    drawItem(g, item, x, y)
                } else {
                    g.color = Color(100, 100, 100, 50)
                    g.fillRect(x, y, slotSize, slotSize)
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
        val texture = loadItemTexture(name, this.javaClass)
        if (texture != null) {
            g.drawImage(texture, x, y, slotSize, slotSize, null)
        } else {
            g.color = Color.GRAY
            g.fillRect(x, y, slotSize, slotSize)
        }
        
        if (item.amount > 1) {
            g.color = Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 16)
            val count = item.amount.toString()
            val w = g.fontMetrics.stringWidth(count)
            g.drawString(count, x + slotSize - w + 2, y + slotSize + 10)
        }
    }
}
