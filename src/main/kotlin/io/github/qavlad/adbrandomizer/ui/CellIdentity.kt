// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/CellIdentity.kt
package io.github.qavlad.adbrandomizer.ui

import java.util.*

/**
 * Уникальная идентификация ячейки таблицы
 */
data class CellIdentity(
    val id: String = UUID.randomUUID().toString()
) {
    companion object {
        /**
         * Создает новый уникальный ID ячейки
         */
        fun generate(): CellIdentity = CellIdentity()
        
        /**
         * Создает ID ячейки из существующей строки
         */
        @Suppress("unused")
        fun fromString(id: String): CellIdentity = CellIdentity(id)
    }
    
    override fun toString(): String = id
}