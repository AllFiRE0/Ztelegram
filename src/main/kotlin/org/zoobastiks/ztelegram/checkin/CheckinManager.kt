package org.zoobastiks.ztelegram.checkin

import org.zoobastiks.ztelegram.ZTele
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.LocalDateTime

class CheckinManager(private val plugin: ZTele) {
    private var connection: Connection? = null

    data class PlayerData(
        val points: Int = 0,
        val totalEarned: Int = 0,
        val streak: Int = 0,
        val lastCheckin: LocalDateTime? = null
    )

    data class CheckinResult(
        val success: Boolean,
        val points: Int,
        val totalPoints: Int,
        val streak: Int,
        val message: String
    )

    init {
        val dbFile = File(plugin.dataFolder, "checkin.db")
        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        connection?.createStatement()?.use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS checkins (
                    player_name TEXT PRIMARY KEY,
                    points INTEGER DEFAULT 0,
                    total_earned INTEGER DEFAULT 0,
                    streak INTEGER DEFAULT 0,
                    last_checkin TEXT
                )
            """)
        }
    }

    private fun getPlayerData(playerName: String): PlayerData? {
        val stmt = connection?.prepareStatement("SELECT * FROM checkins WHERE player_name = ?")
        stmt?.setString(1, playerName.lowercase())
        val rs = stmt?.executeQuery()
        return if (rs?.next() == true) {
            PlayerData(
                rs.getInt("points"),
                rs.getInt("total_earned"),
                rs.getInt("streak"),
                rs.getString("last_checkin")?.let { LocalDateTime.parse(it) }
            )
        } else null
    }

    fun checkin(playerName: String): CheckinResult {
        val now = LocalDateTime.now()
        val data = getPlayerData(playerName)

        // Проверка кулдауна
        if (data?.lastCheckin != null) {
            val diff = Duration.between(data.lastCheckin, now)
            if (diff.toHours() < ZTele.conf.checkinCooldownHours) {
                val remaining = Duration.ofHours(ZTele.conf.checkinCooldownHours.toLong()).minus(diff)
                val h = remaining.toHours()
                val m = remaining.toMinutes() % 60
                return CheckinResult(false, 0, data.points, data.streak,
                    ZTele.conf.checkinMessageCooldown
                        .replace("%time%", "${h}ч ${m}м")
                        .replace("%points%", data.points.toString())
                        .replace("%streak%", data.streak.toString())
                )
            }
        }

        // Расчёт награды
        val reward = if (ZTele.conf.checkinRewardType == "random") {
            (ZTele.conf.checkinRewardMin..ZTele.conf.checkinRewardMax).random()
        } else {
            ZTele.conf.checkinRewardFixed
        }

        // Серия
        val newStreak = if (data?.lastCheckin != null) {
            val days = Duration.between(data.lastCheckin, now).toDays()
            if (days <= 1) (data.streak + 1) else 1
        } else 1

        val streakBonus = if (ZTele.conf.checkinStreakEnabled) {
            (newStreak * 5).coerceAtMost(ZTele.conf.checkinStreakMaxBonus)
        } else 0

        val finalReward = reward + streakBonus
        val newPoints = (data?.points ?: 0) + finalReward
        val newTotalEarned = (data?.totalEarned ?: 0) + finalReward

        // Сохранение
        connection?.prepareStatement("""
            INSERT OR REPLACE INTO checkins (player_name, points, total_earned, streak, last_checkin)
            VALUES (?, ?, ?, ?, ?)
        """)?.use { stmt ->
            stmt.setString(1, playerName.lowercase())
            stmt.setInt(2, newPoints)
            stmt.setInt(3, newTotalEarned)
            stmt.setInt(4, newStreak)
            stmt.setString(5, now.toString())
            stmt.executeUpdate()
        }

        val nextCheckin = now.plusHours(ZTele.conf.checkinCooldownHours.toLong())
        val h = ZTele.conf.checkinCooldownHours

        return CheckinResult(true, finalReward, newPoints, newStreak,
            ZTele.conf.checkinMessageSuccess
                .replace("%points%", finalReward.toString())
                .replace("%total%", newPoints.toString())
                .replace("%streak%", newStreak.toString())
                .replace("%cooldown%", "${h}ч")
        )
    }

    fun getPoints(playerName: String): Int {
        return getPlayerData(playerName)?.points ?: 0
    }

    fun getStreak(playerName: String): Int {
        return getPlayerData(playerName)?.streak ?: 0
    }

    fun getRemainingTime(playerName: String): String {
        val data = getPlayerData(playerName) ?: return "0ч"
        if (data.lastCheckin == null) return "0ч"
        
        val now = LocalDateTime.now()
        val nextCheckin = data.lastCheckin.plusHours(ZTele.conf.checkinCooldownHours.toLong())
        val remaining = Duration.between(now, nextCheckin)
        
        return if (remaining.isNegative || remaining.isZero) {
            "0ч"
        } else {
            val hours = remaining.toHours()
            val minutes = remaining.toMinutes() % 60
            if (hours > 0) "${hours}ч ${minutes}м" else "${minutes}м"
        }
    }

    fun mergeAccounts(playerName: String, telegramId: Long) {
        val tgKey = "tg_$telegramId"
        val tgData = getPlayerData(tgKey) ?: return
        val playerData = getPlayerData(playerName)
        
        val newPoints = (playerData?.points ?: 0) + tgData.points
        val newTotalEarned = (playerData?.totalEarned ?: 0) + tgData.totalEarned
        val newStreak = maxOf(playerData?.streak ?: 0, tgData.streak)
        val lastCheckin = listOfNotNull(playerData?.lastCheckin, tgData.lastCheckin).maxOrNull()
        
        connection?.prepareStatement("""
            INSERT OR REPLACE INTO checkins (player_name, points, total_earned, streak, last_checkin)
            VALUES (?, ?, ?, ?, ?)
        """)?.use { stmt ->
            stmt.setString(1, playerName.lowercase())
            stmt.setInt(2, newPoints)
            stmt.setInt(3, newTotalEarned)
            stmt.setInt(4, newStreak)
            stmt.setString(5, lastCheckin?.toString())
            stmt.executeUpdate()
        }
        
        connection?.prepareStatement("DELETE FROM checkins WHERE player_name = ?")?.use { stmt ->
            stmt.setString(1, tgKey)
            stmt.executeUpdate()
        }
    }

    fun close() = connection?.close()
}
