package com.loopers.pg

enum class TestCard(
    val suffix: String,
    val delaysResponse: Boolean = false,
    val dropsCallback: Boolean = false,
) {
    DECLINED("0000"),
    SLOW("1111", delaysResponse = true),
    SILENT("2222", dropsCallback = true),
    RESPONSE_LOST("3333", delaysResponse = true, dropsCallback = true),
    ;

    companion object {
        fun of(cardNo: String): TestCard? {
            val digits = cardNo.filter { it.isDigit() }

            return entries.firstOrNull { digits.endsWith(it.suffix) }
        }
    }
}
