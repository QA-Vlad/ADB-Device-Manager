// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/HoverState.kt
package io.github.qavlad.adbrandomizer.ui

/**
 * Состояние hover эффектов для элементов списка устройств
 */
data class HoverState(
    val hoveredIndex: Int = -1,
    val hoveredButtonType: String? = null
) {

    /**
     * Проверяет, находится ли указанная кнопка в состоянии hover
     */
    fun isButtonHovered(index: Int, buttonType: String): Boolean {
        return hoveredIndex == index && hoveredButtonType == buttonType
    }

    /**
     * Проверяет, есть ли активный hover на любой кнопке
     */
    fun hasActiveHover(): Boolean {
        return hoveredIndex != -1 && hoveredButtonType != null
    }

    companion object {
        const val BUTTON_TYPE_MIRROR = "MIRROR"
        const val BUTTON_TYPE_WIFI = "WIFI"

        /**
         * Создает состояние без hover эффектов
         */
        fun noHover(): HoverState = HoverState()

        /**
         * Создает состояние с hover на указанной кнопке
         */
        fun hovering(index: Int, buttonType: String): HoverState {
            return HoverState(index, buttonType)
        }
    }
}