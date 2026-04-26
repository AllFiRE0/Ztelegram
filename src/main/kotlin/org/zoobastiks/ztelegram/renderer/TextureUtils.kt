package org.zoobastiks.ztelegram.renderer

import java.awt.image.BufferedImage
import java.io.InputStream
import javax.imageio.ImageIO
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta

fun loadItemTexture(itemName: String, javaClass: Class<out Any>): BufferedImage? {
    val texturePath = "/textures/minecraft__$itemName.png"
    val inputStream: InputStream? = javaClass.getResourceAsStream(texturePath)
    return try {
        inputStream?.let { ImageIO.read(it) }
    } catch (e: Exception) { null }
}

fun loadPotionTexture(item: ItemStack, javaClass: Class<out Any>): BufferedImage? {
    val meta = item.itemMeta
    if (meta is PotionMeta) {
        val potionType = meta.basePotionType?.name?.lowercase()
        return loadItemTexture("potion__$potionType", javaClass)
    }
    return null
}

fun loadMapTexture(javaClass: Class<out Any>): BufferedImage? {
    return loadItemTexture("map", javaClass)
}

fun loadInventoryBackground(javaClass: Class<out Any>): BufferedImage? {
    return loadItemTexture("inventory_background", javaClass)
}