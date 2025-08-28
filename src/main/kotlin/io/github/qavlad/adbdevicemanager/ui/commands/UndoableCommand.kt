package io.github.qavlad.adbdevicemanager.ui.commands

/**
 * Базовый интерфейс для всех команд, поддерживающих отмену/повтор
 */
interface UndoableCommand {
    /**
     * Выполняет команду
     */
    fun execute()
    
    /**
     * Отменяет выполнение команды
     */
    fun undo()
    
    /**
     * Повторяет выполнение команды после отмены
     */
    fun redo()
    
    /**
     * Описание команды для отображения в истории
     */
    val description: String
}