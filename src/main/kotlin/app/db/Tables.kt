package app.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object UsersTable : LongIdTable("users") {
    val telegramId = long("telegram_id").uniqueIndex()
    val locale = varchar("locale", length = 8).nullable()
    val telegramLangCode = varchar("telegram_lang_code", length = 8).nullable()
    val languageSelected = bool("language_selected").default(false)
    val conversationState = varchar("conversation_state", length = 32).nullable()
    val mode = varchar("mode", length = 32).nullable()
    val activeMode = varchar("active_mode", length = 32).nullable()
    val lastMenuMessageId = long("last_menu_message_id").nullable()
    val lastWelcomeImageMessageId = long("last_welcome_image_message_id").nullable()
    val lastWelcomeGreetingMessageId = long("last_welcome_greeting_message_id").nullable()
    val lastStartCommandMessageId = long("last_start_command_message_id").nullable()
    val createdAt = datetime("created_at")
}

object PremiumTable : LongIdTable("premium") {
    val telegramId = long("telegram_id").uniqueIndex()
    val activeUntil = datetime("active_until")
}

object UsageCountersTable : LongIdTable("usage_counters") {
    val telegramId = long("telegram_id").uniqueIndex()
    val totalUsed = integer("total_used").default(0)
}

object MessagesHistoryTable : LongIdTable("messages_history") {
    val telegramId = long("telegram_id")
    val role = varchar("role", length = 32)
    val content = text("content")
    val createdAt = datetime("created_at")
}

object ProcessedUpdatesTable : Table("processed_updates") {
    val updateId = long("update_id").uniqueIndex()
    val processedAt = datetime("processed_at")
    override val primaryKey = PrimaryKey(updateId)
}
