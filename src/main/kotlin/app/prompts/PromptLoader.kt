package app.prompts

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

internal object PromptLoader {
    private val cache = ConcurrentHashMap<String, String>()

    fun load(name: String): String {
        return cache.computeIfAbsent(name) { resourceName ->
            val path = "prompts/$resourceName"
            val stream = javaClass.classLoader?.getResourceAsStream(path)
                ?: error("Prompt resource not found: $path")
            stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }.trim()
        }
    }
}
