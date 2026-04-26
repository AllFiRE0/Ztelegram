package org.zoobastiks.ztelegram.placeholders

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player
import org.zoobastiks.ztelegram.ZTele

class TGBridgeExpansion : PlaceholderExpansion() {
    
    override fun getIdentifier(): String = "ztelegram"
    override fun getAuthor(): String = "Zoobastiks"
    override fun getVersion(): String = "1.0"
    override fun persist(): Boolean = true
    
    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (player == null) return null
        
        return when (params) {
            "checkin_points" -> {
                ZTele.checkinManager.getPoints(player.name).toString()
            }
            "checkin_streak" -> {
                ZTele.checkinManager.getStreak(player.name).toString()
            }
            "checkin_remaining" -> {
                ZTele.checkinManager.getRemainingTime(player.name)
            }
            "rep_total" -> {
                ZTele.reputation.getReputationData(player.name).totalReputation.toString()
            }
            "rep_positive" -> {
                ZTele.reputation.getReputationData(player.name).positiveRep.toString()
            }
            "rep_negative" -> {
                ZTele.reputation.getReputationData(player.name).negativeRep.toString()
            }
            "rep_level" -> {
                ZTele.reputation.getReputationData(player.name).reputationLevel.displayName
            }
            else -> null
        }
    }
}