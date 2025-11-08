package app.services

import app.Update

interface UpdateHandler {
    suspend fun handle(update: Update)
}
