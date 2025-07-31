package io.github.qavlad.adbrandomizer.ui.services

import com.intellij.ui.table.JBTable
import java.awt.AWTEvent
import java.awt.Container
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton

/**
 * Менеджер для управления глобальными слушателями событий.
 * Централизует логику работы с глобальными обработчиками кликов и других событий.
 */
class GlobalListenersManager {
    
    private var globalClickListener: java.awt.event.AWTEventListener? = null
    
    /**
     * Устанавливает глобальный слушатель кликов для выхода из режима редактирования таблицы
     */
    fun setupGlobalClickListener(
        table: JBTable,
        onTableCellEditorStop: () -> Unit
    ): java.awt.event.AWTEventListener {
        // Удаляем предыдущий слушатель если есть
        removeGlobalClickListener()
        
        globalClickListener = java.awt.event.AWTEventListener { event ->
            if (event.id == MouseEvent.MOUSE_CLICKED && event is MouseEvent) {
                // Проверяем, что клик не по самой таблице
                val source = event.source
                if (source !is JBTable && table.isEditing) {
                    println("ADB_DEBUG: Global click detected outside table - stopping cell editing")
                    onTableCellEditorStop()
                }
            }
        }
        
        // Добавляем слушатель в глобальную очередь событий
        Toolkit.getDefaultToolkit().addAWTEventListener(
            globalClickListener,
            AWTEvent.MOUSE_EVENT_MASK
        )
        
        return globalClickListener!!
    }
    
    /**
     * Добавляет обработчики кликов рекурсивно ко всем компонентам контейнера
     * для выхода из режима редактирования при клике вне таблицы
     */
    fun addClickListenerRecursively(
        container: Container,
        table: JBTable
    ) {
        var listenerCount = 0
        
        val mouseListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Проверяем, что клик не по самой таблице и не по кнопкам
                if (e.source !is JBTable && e.source !is JButton && table.isEditing) {
                    println("ADB_DEBUG: Cell editing stopped by recursive click listener")
                    table.cellEditor?.stopCellEditing()
                }
            }
        }
        
        fun addListenersRecursive(comp: Container) {
            // Добавляем обработчик к контейнеру
            comp.addMouseListener(mouseListener)
            listenerCount++
            
            // Рекурсивно обрабатываем все дочерние компоненты
            for (component in comp.components) {
                when (component) {
                    is JBTable -> {
                        // Пропускаем таблицу
                    }
                    is JButton -> {
                        // Пропускаем кнопки
                    }
                    is Container -> {
                        addListenersRecursive(component)
                    }
                    else -> {
                        // Добавляем обработчик к обычным компонентам
                        component.addMouseListener(mouseListener)
                        listenerCount++
                    }
                }
            }
        }
        
        addListenersRecursive(container)
        println("ADB_DEBUG: Added click listeners to $listenerCount components")
    }
    
    /**
     * Удаляет глобальный слушатель кликов
     */
    fun removeGlobalClickListener() {
        globalClickListener?.let {
            Toolkit.getDefaultToolkit().removeAWTEventListener(it)
            globalClickListener = null
        }
    }
    
    
    /**
     * Очищает все слушатели
     */
    fun dispose() {
        removeGlobalClickListener()
    }
}