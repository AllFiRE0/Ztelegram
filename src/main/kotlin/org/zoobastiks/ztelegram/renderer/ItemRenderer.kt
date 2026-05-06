package org.zoobastiks.ztelegram.renderer

import java.awt.Font
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
            height += 20
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
        var cleanText = text.replace(Regex("§x(§[0-9a-fA-F]){6}"), "")
        
        // Конвертируем названия цветов в HEX {#white} → {#FFFFFF}
        val colorNames = mapOf(
            "white" to "FFFFFF", "black" to "000000", "red" to "FF0000",
            "green" to "00FF00", "blue" to "0000FF", "yellow" to "FFFF00",
            "aqua" to "00FFFF", "pink" to "FFC0CB", "gray" to "808080",
            "grey" to "808080", "orange" to "FFA500", "purple" to "800080"
        )
        cleanText = cleanText.replace(Regex("\\{#([a-zA-Z]+)\\}")) { match ->
            val name = match.groupValues[1].lowercase()
            val hex = colorNames[name] ?: "FFFFFF"
            "{#$hex}"
        }
        
        // CMI градиент {#RRGGBB>}текст{#RRGGBB<}
        val cmiGradientPattern = Regex("\\{#([0-9a-fA-F]{6})>\\}([^\\{]*)\\{#([0-9a-fA-F]{6})<\\}")
        var cmiMatch = cmiGradientPattern.find(cleanText)
        while (cmiMatch != null) {
            val hex1 = cmiMatch.groupValues[1]
            val hex2 = cmiMatch.groupValues[3]
            val content = cmiMatch.groupValues[2]
            val replacement = "<gradient:#$hex1:#$hex2>$content</gradient>"
            cleanText = cleanText.replace(cmiMatch.value, replacement)
            cmiMatch = cmiGradientPattern.find(cleanText)
        }
        
        // CMI JSON [{color:"#RRGGBB",text:"символ"},...]
        val cmiJsonPattern = Regex("\\{bold:[^,]*,color:\"#([0-9a-fA-F]{6})\"[^}]*text:\"([^\"]+)\"[^}]*\\}")
        val cmiJsonMatches = cmiJsonPattern.findAll(cleanText).toList()
        if (cmiJsonMatches.size > 1) {
            val firstColor = cmiJsonMatches.first().groupValues[1]
            val lastColor = cmiJsonMatches.last().groupValues[1]
            val allText = cmiJsonMatches.joinToString("") { it.groupValues[2] }
            val replacement = "<gradient:#$firstColor:#$lastColor>$allText</gradient>"
            val fullMatch = "[${cmiJsonMatches.joinToString(",") { it.value }}]"
            cleanText = cleanText.replace(fullMatch, replacement)
        }
        
        // MiniMessage <color:#RRGGBB>текст</color>
        cleanText = cleanText.replace(Regex("<color:#([0-9a-fA-F]{6})>([^<]*)</color>")) { match ->
            "&#${match.groupValues[1]}${match.groupValues[2]}"
        }
        
        // MiniMessage <gradient:#RRGGBB:#RRGGBB>текст</gradient>
        val gradientPattern = Regex("<gradient:#([0-9a-fA-F]{6}):#([0-9a-fA-F]{6})>([^<]*)</gradient>")
        var matchResult = gradientPattern.find(cleanText)
        while (matchResult != null) {
            val hex1 = matchResult.groupValues[1]
            val hex2 = matchResult.groupValues[2]
            val content = matchResult.groupValues[3]
            val c1 = Color.decode("#$hex1")
            val c2 = Color.decode("#$hex2")
            
            val before = cleanText.substring(0, matchResult.range.first)
            val after = cleanText.substring(matchResult.range.last + 1)
            
            val beforeSegments = before.split(Regex("(?=&[0-9a-fA-Fk-oK-OrR#])|(?=§[0-9a-fA-Fk-oK-OrR#])"))
            var beforeX = drawSegments(g, beforeSegments, x, y, defaultColor, g.font)
            
            val textWidth = g.fontMetrics.stringWidth(cleanContent)
            val cleanContent = content.replace(Regex("[§&][0-9a-fA-Fk-oK-OrR]"), "")
            val gradientPaint = java.awt.GradientPaint(
                beforeX.toFloat(), 0f, c1,
                (beforeX + textWidth).toFloat(), 0f, c2
            )
            val oldPaint = g.paint
            g.paint = gradientPaint
            g.font = currentFont
            g.drawString(cleanContent, beforeX, y)
            g.paint = oldPaint
            beforeX += textWidth
            
            cleanText = after
            matchResult = gradientPattern.find(cleanText)
        }
        
        val segments = cleanText.split(Regex("(?=&[0-9a-fA-Fk-oK-OrR#])|(?=§[0-9a-fA-Fk-oK-OrR#])"))
        drawSegments(g, segments, x, y, defaultColor, g.font)
    }

    private var currentFont: Font = Font("SansSerif", Font.PLAIN, 16)

    private fun drawSegments(g: Graphics2D, segments: List<String>, x: Int, y: Int, defaultColor: Color, baseFont: Font): Int {
        var currentX = x
        var currentColor = defaultColor
        currentFont = baseFont
        
        for (segment in segments) {
            if (segment.isEmpty()) continue
            
            when {
                segment.matches(Regex("^[§&]#[0-9a-fA-F]{6}.*")) -> {
                    val hex = segment.substring(2, 8)
                    try { currentColor = Color.decode("#$hex") } catch (e: Exception) {}
                    g.color = currentColor
                    g.font = currentFont
                    g.drawString(segment.substring(8), currentX, y)
                    currentX += g.fontMetrics.stringWidth(segment.substring(8))
                }
                (segment.startsWith("§") || segment.startsWith("&")) && segment.length >= 2 -> {
                    val code = segment[1].lowercaseChar()
                    when (code) {
                        'l' -> currentFont = currentFont.deriveFont(Font.BOLD)
                        'o' -> currentFont = currentFont.deriveFont(Font.ITALIC)
                        'n' -> {}
                        'm' -> {}
                        'r' -> {
                            currentFont = baseFont
                            currentColor = defaultColor
                        }
                        'k' -> {}
                        '#' -> {}
                        else -> currentColor = colorMap[code] ?: defaultColor
                    }
                    if (code != '#' && segment.length > 2) {
                        g.color = currentColor
                        g.font = currentFont
                        g.drawString(segment.substring(2), currentX, y)
                        currentX += g.fontMetrics.stringWidth(segment.substring(2))
                    }
                }
                else -> {
                    g.color = currentColor
                    g.font = currentFont
                    g.drawString(segment, currentX, y)
                    currentX += g.fontMetrics.stringWidth(segment)
                }
            }
        }
        return currentX
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
            .replace(Regex("§x(§[0-9a-fA-F]){6}"), "")
            .replace(Regex("[§&][0-9a-fk-orA-FK-OR]"), "")
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
        
        currentYOffset += 20
        
        for (line in lore) {
            if (line.isEmpty()) {
                currentYOffset += 20
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
