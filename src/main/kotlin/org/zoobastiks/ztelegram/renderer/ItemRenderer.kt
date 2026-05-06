package org.zoobastiks.ztelegram.renderer

import java.awt.Font
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.Color
import java.awt.Graphics2D
import java.io.ByteArrayOutputStream
import org.zoobastiks.ztelegram.ZTele

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

    private var baseFont: Font = Font("SansSerif", Font.PLAIN, 16)
    private var currentFont: Font = baseFont

    fun renderItemToFile(item: ItemStack): Pair<ByteArray, String> {
        debugLog("Starting item rendering for: ${item.type}")
        
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
        
        debugLog("Item rendering completed. Image size: ${imageBytes.size} bytes")
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
        debugLog("Drawing colored string: '${text.take(50)}...' at x=$x, y=$y")
        
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
        val cmiGradientPattern = Regex("\\{#([0-9a-fA-F]{6})>\\}([^\\}]*)\\{#([0-9a-fA-F]{6})<\\}")
        var cmiMatch = cmiGradientPattern.find(cleanText)
        while (cmiMatch != null) {
            val hex1 = cmiMatch.groupValues[1]
            val hex2 = cmiMatch.groupValues[3]
            val content = cmiMatch.groupValues[2]
            val replacement = "<gradient:#$hex1:#$hex2>$content</gradient>"
            debugLog("Converting CMI gradient: {#$hex1>}$content{#$hex2<}")
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
            debugLog("Converting CMI JSON gradient from #$firstColor to #$lastColor")
            val fullMatch = "\\[${cmiJsonMatches.joinToString(",") { Regex.escape(it.value) }}\\]"
            cleanText = cleanText.replace(Regex(fullMatch), replacement)
        }
        
        // MiniMessage <color:#RRGGBB>текст</color>
        cleanText = cleanText.replace(Regex("<color:#([0-9a-fA-F]{6})>([^<]*)</color>")) { match ->
            "&#${match.groupValues[1]}${match.groupValues[2]}"
        }
        
        // MiniMessage <gradient:#RRGGBB:#RRGGBB>текст</gradient>
        val gradientPattern = Regex("<gradient:#([0-9a-fA-F]{6}):#([0-9a-fA-F]{6})>([^<]*)</gradient>")
        var currentX = x
        var matchResult = gradientPattern.find(cleanText)
        while (matchResult != null) {
            val hex1 = matchResult.groupValues[1]
            val hex2 = matchResult.groupValues[2]
            val content = matchResult.groupValues[3]
            val c1 = Color.decode("#$hex1")
            val c2 = Color.decode("#$hex2")
            
            debugLog("Found gradient: #$hex1 to #$hex2 with text: '${content.take(30)}'")
            
            val before = cleanText.substring(0, matchResult.range.first)
            val after = cleanText.substring(matchResult.range.last + 1)
            
            val beforeSegments = before.split(Regex("(?=&[0-9a-fA-Fk-oK-OrR#])|(?=§[0-9a-fA-Fk-oK-OrR#])"))
            currentX = drawSegments(g, beforeSegments, currentX, y, defaultColor, baseFont)
            
            val cleanContent = content.replace(Regex("[§&][0-9a-fA-Fk-oK-OrR]"), "")
            val textWidth = g.fontMetrics.stringWidth(cleanContent)
            val gradientPaint = java.awt.GradientPaint(
                currentX.toFloat(), 0f, c1,
                (currentX + textWidth).toFloat(), 0f, c2
            )
            val oldPaint = g.paint
            g.paint = gradientPaint
            g.font = currentFont
            g.drawString(cleanContent, currentX, y)
            g.paint = oldPaint
            currentX += textWidth
            
            cleanText = after
            matchResult = gradientPattern.find(cleanText)
        }
        
        val segments = cleanText.split(Regex("(?=&[0-9a-fA-Fk-oK-OrR#])|(?=§[0-9a-fA-Fk-oK-OrR#])"))
        drawSegments(g, segments, currentX, y, defaultColor, baseFont)
    }

    private fun drawSegments(g: Graphics2D, segments: List<String>, x: Int, y: Int, defaultColor: Color, baseFont: Font): Int {
        var currentX = x
        var currentColor = defaultColor
        currentFont = baseFont
        
        for (segment in segments) {
            if (segment.isEmpty()) continue
            
            when {
                segment.matches(Regex("^[§&]#[0-9a-fA-F]{6}.*")) -> {
                    val hex = segment.substring(2, 8)
                    try { 
                        currentColor = Color.decode("#$hex")
                        debugLog("Applied HEX color: #$hex")
                    } catch (e: Exception) {
                        debugLog("Failed to decode HEX color: #$hex - ${e.message}")
                    }
                    g.color = currentColor
                    g.font = currentFont
                    g.drawString(segment.substring(8), currentX, y)
                    currentX += g.fontMetrics.stringWidth(segment.substring(8))
                }
                (segment.startsWith("§") || segment.startsWith("&")) && segment.length >= 2 -> {
                    val code = segment[1].lowercaseChar()
                    when (code) {
                        'l' -> {
                            currentFont = currentFont.deriveFont(Font.BOLD)
                            debugLog("Applied BOLD formatting")
                        }
                        'o' -> {
                            currentFont = currentFont.deriveFont(Font.ITALIC)
                            debugLog("Applied ITALIC formatting")
                        }
                        'n' -> {
                            debugLog("Strikethrough code ignored (not supported in AWT)")
                        }
                        'm' -> {
                            debugLog("Magic code ignored (not supported in AWT)")
                        }
                        'r' -> {
                            currentFont = baseFont
                            currentColor = defaultColor
                            debugLog("Reset formatting and color")
                        }
                        'k' -> {
                            debugLog("Obfuscation code ignored (not supported in AWT)")
                        }
                        '#' -> {
                            debugLog("HEX color code indicator")
                        }
                        else -> {
                            val newColor = colorMap[code]
                            if (newColor != null) {
                                currentColor = newColor
                                debugLog("Applied Minecraft color code: &$code")
                            }
                        }
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
        debugLog("Item name: '$fullName'")
        
        val nameColor = determineNameColor(item)
        g.font = MinecraftFontLoader.getFont(16f)
        baseFont = g.font
        currentFont = baseFont
        drawColoredString(g, fullName, margin, imageScale + margin + 30, nameColor)
        return fullName
            .replace(Regex("§x(§[0-9a-fA-F]){6}"), "")
            .replace(Regex("[§&][0-9a-fk-orA-FK-OR]"), "")
    }

    private fun determineNameColor(item: ItemStack): Color {
        return when {
            item.itemMeta?.hasEnchants() == true || (item.itemMeta is EnchantmentStorageMeta && (item.itemMeta as EnchantmentStorageMeta).storedEnchants.isNotEmpty()) -> {
                debugLog("Enchanted item detected - using CYAN color")
                Color.CYAN
            }
            item.type.name.contains("totem", ignoreCase = true) || item.type.name.contains("book", ignoreCase = true) -> {
                debugLog("Totem or Book detected - using YELLOW color")
                Color.YELLOW
            }
            else -> {
                debugLog("Regular item - using WHITE color")
                Color.WHITE
            }
        }
    }

    private fun drawLore(g: Graphics2D, item: ItemStack, textYOffset: Int): Int {
        val lore = item.itemMeta?.lore ?: return textYOffset
        if (lore.isEmpty()) return textYOffset

        debugLog("Drawing lore with ${lore.size} lines")
        g.font = MinecraftFontLoader.getFont(14f)
        baseFont = g.font
        currentFont = baseFont
        var currentYOffset = textYOffset
        
        currentYOffset += 20
        
        for ((index, line) in lore.withIndex()) {
            if (line.isEmpty()) {
                currentYOffset += 20
                continue
            }
            debugLog("Lore line $index: '${line.take(50)}'")
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
            debugLog("Drawing ${enchantments.size} enchantments")
            g.font = MinecraftFontLoader.getFont(14f)
            baseFont = g.font
            currentFont = baseFont
            g.color = Color.decode(enchantmentColor)
            var currentYOffset = textYOffset
            for ((enchantment, level) in enchantments) {
                val name = ItemTranslator.translateEnchantment(enchantment.key.key, level)
                debugLog("Enchantment: $name (level $level)")
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
            baseFont = g.font
            currentFont = baseFont
            g.color = Color.WHITE
            val currentDurability = item.type.maxDurability - (item.itemMeta as org.bukkit.inventory.meta.Damageable).damage
            val durabilityText = "Durability: $currentDurability/${item.type.maxDurability}"
            debugLog(durabilityText)
            g.drawString(durabilityText, margin, textYOffset)
        }
    }

    private fun drawStackSize(g: Graphics2D, item: ItemStack) {
        if (item.amount > 1) {
            g.font = MinecraftFontLoader.getFont(20f)
            g.color = Color.WHITE
            val stackSize = "x ${item.amount}"
            val x = margin + imageScale + 10
            val y = margin + imageScale - 5
            debugLog("Stack size: ${item.amount}")
            g.drawString(stackSize, x, y)
        }
    }

    private fun debugLog(message: String) {
        if (ZTele.conf.debugEnabled) {
            ZTele.instance.logger.info("[ItemRenderer] $message")
        }
    }
}
