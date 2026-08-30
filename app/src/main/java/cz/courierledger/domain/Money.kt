package cz.courierledger.domain

@JvmInline value class Money(val hellers: Long) {
    operator fun plus(other: Money) = Money(hellers + other.hellers)
    operator fun minus(other: Money) = Money(hellers - other.hellers)
    operator fun times(count: Int) = Money(hellers * count)
    fun czkText(): String = if (hellers % 100L == 0L) "${hellers / 100} Kč" else "%.2f Kč".format(hellers / 100.0)
    companion object { val ZERO = Money(0) }
}
