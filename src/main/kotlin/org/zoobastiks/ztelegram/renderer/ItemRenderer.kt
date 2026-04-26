package org.zoobastiks.ztelegram.renderer

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ItemRenderer {
    private val width = 250
    private val imageScale = 48
    private val margin = 12
    private val backgroundColor = Color(33, 9, 57)
    private val borderColor = Color(26, 11, 26)
    private val enchantmentColor = Color(167, 167, 167)

    fun renderItem(item: ItemStack): ByteArray {
        val texture = loadTexture(item)
        val height = calculateHeight(item)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()

        // Фон
        g.color = backgroundColor
        g.fillRect(0, 0, width, height)
        g.color = borderColor
        g.fillRect(4, 4, width - 8, height - 8)

        // Текстура
        if (texture != null) {
            g.drawImage(texture, margin, margin, imageScale, imageScale, null)
        } else {
            g.color = Color.GRAY
            g.fillRect(margin, margin, imageScale, imageScale)
        }

        // Название
        g.font = Font("SansSerif", Font.BOLD, 16)
        val displayName = PlainTextComponentSerializer.plainText().serialize(item.displayName())
        val itemName = if (displayName.isNotEmpty()) {
            displayName
        } else {
            ItemTranslator.translateItem(item.type.name)
        }
        g.color = when {
            item.itemMeta?.hasEnchants() == true -> Color.CYAN
            item.type.name.contains("TOTEM", true) -> Color.YELLOW
            else -> Color.WHITE
        }
        g.drawString(itemName, margin, imageScale + margin + 30)

        var yOffset = imageScale + margin + 50

        // Зачарования
        val enchants = if (item.itemMeta is EnchantmentStorageMeta) {
            (item.itemMeta as EnchantmentStorageMeta).storedEnchants
        } else item.enchantments

        if (enchants.isNotEmpty()) {
            g.font = Font("SansSerif", Font.PLAIN, 14)
            g.color = enchantmentColor
            for ((ench, level) in enchants) {
                val name = ItemTranslator.translateEnchantment(ench.key.key, level)
                g.drawString(name, margin, yOffset)
                yOffset += 20
            }
        }

        // Прочность
        if (item.itemMeta is Damageable && item.type.maxDurability > 0) {
            g.font = Font("SansSerif", Font.PLAIN, 14)
            g.color = Color.WHITE
            val meta = item.itemMeta as Damageable
            val dura = item.type.maxDurability - meta.damage
            g.drawString("Прочность: $dura/${item.type.maxDurability}", margin, yOffset)
        }

        // Количество
        if (item.amount > 1) {
            g.font = Font("SansSerif", Font.BOLD, 20)
            g.color = Color.WHITE
            g.drawString("x${item.amount}", margin + imageScale + 10, margin + imageScale - 5)
        }

        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun loadTexture(item: ItemStack): BufferedImage? {
        val name = item.type.name.lowercase()
        return when {
            name.contains("potion") -> loadPotionTexture(item, this.javaClass)
            name.contains("map") -> loadMapTexture(this.javaClass)
            else -> loadItemTexture(name, this.javaClass)
        }
    }

    private fun calculateHeight(item: ItemStack): Int {
        var height = imageScale + margin * 2 + 40
        val enchants = (item.itemMeta as? EnchantmentStorageMeta)?.storedEnchants ?: item.enchantments
        height += enchants.size * 20
        if (item.itemMeta is Damageable && item.type.maxDurability > 0) height += 20
        return height + margin
    }
}