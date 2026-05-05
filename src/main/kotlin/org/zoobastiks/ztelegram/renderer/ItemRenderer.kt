package org.zoobastiks.ztelegram.renderer

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.Color
import java.awt.Graphics2D
import java.io.ByteArrayOutputStream

class ItemRenderer {
    private val width = 250
    private val imageScale = 48
    private val margin = 12
    private val backgroundColor = "#210939"
    private val borderColor = "#1A0B1A"
    private val enchantmentColor = "#A7A7A7"

    private val colorMap = mapOf(
        '0' to Color.BLACK, '1' to Color(0, 0, 170), '2' to Color(0, 170, 0),
        '3' to Color(0, 170, 170), '4' to Color(170, 0, 0), '5' to Color(170, 0, 170),
        '6' to Color(255, 170, 0), '7' to Color(170, 170, 170), '8' to Color(85, 85, 85),
        '9' to Color(85, 85, 255), 'a' to Color(85, 255, 85), 'b' to Color(85, 255, 255),
        'c' to Color(255, 85, 85), 'd' to Color(255, 85, 255), 'e' to Color(255, 255, 85),
        'f' to Color.WHITE
    )

    fun renderItemToFile(item: ItemStack): Pair<ByteArray, String> {
        val texture = loadTexture(item)
        val height = calculateDynamicHeight(item)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()

        drawBackground(g, height)
        drawTexture(g, texture)
        val itemName = drawItemName(g, item)

        var textYOffset = imageScale + margin + 50

        textYOffset = drawLore(g, item, textYOffset)
        textYOffset = drawEnchantments(g, item, textYOffset)
        drawDurability(g, item, textYOffset)
        drawStackSize(g, item)

        g.dispose()

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "png", outputStream)
        val imageBytes = outputStream.toByteArray()
        outputStream.close()
        return Pair(imageBytes, itemName)
    }

    private fun loadTexture(item: ItemStack): BufferedImage? {
        return when (val itemName = item.type.name.lowercase()) {
            "potion", "splash_potion", "lingering_potion" ->
                loadPotionTexture(item, this.javaClass) ?: loadAwkwardPotionTexture(this.javaClass)
            "filled_map" -> loadMapTexture(this.javaClass)
            else -> loadItemTexture(itemName, this.javaClass)
        }
    }

    private fun calculateDynamicHeight(item: ItemStack): Int {
        var height = imageScale + margin * 2 + 30

        val lore = item.itemMeta?.lore
        if (lore != null) {
            height += 20  // отступ перед лором
            height += 20 * lore.size
        }

        val enchantments = if (item.itemMeta is EnchantmentStorageMeta) {
            (item.itemMeta as EnchantmentStorageMeta).storedEnchants
        } else {
            item.enchantments
        }
        if (enchantments.isNotEmpty()) height += 20 * enchantments.size

        if (item.itemMeta is org.bukkit.inventory.meta.Damageable && item.type.maxDurability > 0) height += 20

        return height + margin
    }

    private fun drawBackground(g: Graphics2D, height: Int) {
        g.color = Color.decode(backgroundColor)
        g.fillRect(0, 0, width, height)
        g.color = Color.decode(borderColor)
        g.fillRect(4, 4, width - 8, height - 8)
    }

    private fun drawTexture(g: Graphics2D, texture: BufferedImage?) {
        if (texture == null) {
            g.color = Color.GRAY
            g.fillRect(margin, margin, imageScale, imageScale)
        } else {
            g.drawImage(texture, margin, margin, imageScale, imageScale, null)
        }
    }

    private fun drawColoredString(g: Graphics2D, text: String, x: Int, y: Int, defaultColor: Color) {
        val cleanText = text.replace("&", "§")
        val parts = cleanText.split(Regex("(?=&[0-9a-fA-F#])|(?=§[0-9a-fA-F#])"))
        
        var currentX = x
        for (part in parts) {
            when {
                part.matches(Regex("^[&§]#[0-9a-fA-F]{6}.*").replace("&", "§")) -> {
                    // HEX цвет
                    val hex = part.substring(1, 8)  // §#RRGGBB
                    g.color = Color.decode("0x${hex.substring(1)}")
                    g.drawString(part.substring(8), currentX, y)
                    currentX += g.fontMetrics.stringWidth(part.substring(8))
                }
                part.startsWith("§") && part.length >= 2 -> {
                    g.color = colorMap[part[1].lowercaseChar()] ?: defaultColor
                    g.drawString(part.substring(2), currentX, y)
                    currentX += g.fontMetrics.stringWidth(part.substring(2))
                }
                else -> {
                    g.color = defaultColor
                    g.drawString(part, currentX, y)
                    currentX += g.fontMetrics.stringWidth(part)
                }
            }
        }
    }

    private fun drawItemName(g: Graphics2D, item: ItemStack): String {
        val fullName = if (item.itemMeta?.hasDisplayName() == true) {
            item.itemMeta.displayName
        } else {
            ItemTranslator.translateItem(item.type.name)
        }
        val nameColor = determineNameColor(item)
        g.font = MinecraftFontLoader.getFont(16f)
        drawColoredString(g, fullName, margin, imageScale + margin + 30, nameColor)
        return fullName
    }

    private fun determineNameColor(item: ItemStack): Color {
        return when {
            item.itemMeta?.hasEnchants() == true || (item.itemMeta is EnchantmentStorageMeta && (item.itemMeta as EnchantmentStorageMeta).storedEnchants.isNotEmpty()) -> Color.CYAN
            item.type.name.contains("totem", ignoreCase = true) || item.type.name.contains("book", ignoreCase = true) -> Color.YELLOW
            else -> Color.WHITE
        }
    }

    private fun drawLore(g: Graphics2D, item: ItemStack, textYOffset: Int): Int {
        val lore = item.itemMeta?.lore ?: return textYOffset
        if (lore.isEmpty()) return textYOffset

        g.font = MinecraftFontLoader.getFont(14f)
        var currentYOffset = textYOffset
        
        // Пустая строка перед описанием
        currentYOffset += 20
        
        for (line in lore) {
            if (line.isEmpty()) {
                currentYOffset += 20  // рендерит пустые строки
                continue
            }
            drawColoredString(g, line, margin, currentYOffset, Color.decode("#AAAAAA"))
            currentYOffset += 20
        }
        return currentYOffset
    }

    private fun drawEnchantments(g: Graphics2D, item: ItemStack, textYOffset: Int): Int {
        val enchantments = if (item.itemMeta is EnchantmentStorageMeta) {
            (item.itemMeta as EnchantmentStorageMeta).storedEnchants
        } else {
            item.enchantments
        }
        if (enchantments.isNotEmpty()) {
            g.font = MinecraftFontLoader.getFont(14f)
            g.color = Color.decode(enchantmentColor)
            var currentYOffset = textYOffset
            for ((enchantment, level) in enchantments) {
                val name = ItemTranslator.translateEnchantment(enchantment.key.key, level)
                g.drawString(name, margin, currentYOffset)
                currentYOffset += 20
            }
            return currentYOffset
        }
        return textYOffset
    }

    private fun drawDurability(g: Graphics2D, item: ItemStack, textYOffset: Int) {
        if (item.itemMeta is org.bukkit.inventory.meta.Damageable && item.type.maxDurability > 0) {
            g.font = MinecraftFontLoader.getFont(14f)
            g.color = Color.WHITE
            val currentDurability = item.type.maxDurability - (item.itemMeta as org.bukkit.inventory.meta.Damageable).damage
            g.drawString("Durability: $currentDurability/${item.type.maxDurability}", margin, textYOffset)
        }
    }

    private fun drawStackSize(g: Graphics2D, item: ItemStack) {
        if (item.amount > 1) {
            g.font = MinecraftFontLoader.getFont(20f)
            g.color = Color.WHITE
            val stackSize = "x ${item.amount}"
            val x = margin + imageScale + 10
            val y = margin + imageScale - 5
            g.drawString(stackSize, x, y)
        }
    }
}
