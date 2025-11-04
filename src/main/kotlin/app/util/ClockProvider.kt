package app.util

import java.time.Clock

object ClockProvider {
    var clock: Clock = Clock.systemUTC()
        private set

    fun set(clock: Clock) {
        this.clock = clock
    }
}
