package org.zoobastiks.ztelegram.renderer

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.zoobastiks.ztelegram.ZTele
import java.io.File
import java.io.InputStreamReader

object ItemTranslator {
    private val translations = mutableMapOf<String, String>()
    private val enchantmentTranslations = mutableMapOf<String, String>()
    private val gson = Gson()

    fun load() {
        // Загружаем переводы Minecraft из JSON
        val jsonFile = File(ZTele.instance.dataFolder, "translation.json")
        if (!jsonFile.exists()) {
            // Копируем из ресурсов плагина
            ZTele.instance.saveResource("translation.json", false)
        }
        
        if (jsonFile.exists()) {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val allTranslations: Map<String, String> = gson.fromJson(
                InputStreamReader(jsonFile.inputStream(), Charsets.UTF_8), type
            )
            
            // Извлекаем переводы предметов
            for ((key, value) in allTranslations) {
                when {
                    key.startsWith("item.minecraft.") -> {
                        val itemName = key.removePrefix("item.minecraft.")
                        translations[itemName] = value
                    }
                    key.startsWith("block.minecraft.") -> {
                        val blockName = key.removePrefix("block.minecraft.")
                        translations[blockName] = value
                    }
                    key.startsWith("enchantment.minecraft.") -> {
                        val enchName = key.removePrefix("enchantment.minecraft.")
                        enchantmentTranslations[enchName] = value
                    }
                }
            }
            ZTele.instance.logger.info("Загружено ${translations.size} переводов предметов и ${enchantmentTranslations.size} переводов чар")
        }
    }

    fun translateItem(materialName: String): String {
        val key = materialName.lowercase()
        return translations[key] 
            ?: materialName.lowercase()
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
    }

    fun translateEnchantment(enchantmentKey: String, level: Int): String {
        val name = enchantmentTranslations[enchantmentKey.lowercase()] 
            ?: enchantmentKey.lowercase()
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
        
        val romanLevel = when (level) {
            1 -> "I"
            2 -> "II"
            3 -> "III"
            4 -> "IV"
            5 -> "V"
            6 -> "VI"
            7 -> "VII"
            8 -> "VIII"
            9 -> "IX"
            10 -> "X"
            else -> level.toString()
        }
        return "$name $romanLevel"
    }
    
    fun reload() {
        translations.clear()
        enchantmentTranslations.clear()
        load()
    }
}