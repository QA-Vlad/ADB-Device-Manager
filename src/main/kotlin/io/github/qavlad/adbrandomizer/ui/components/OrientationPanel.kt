package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.ui.services.SelectionTracker
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.awt.*
import javax.swing.*
import javax.swing.event.TableColumnModelListener
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.ChangeEvent
import javax.swing.event.ListSelectionEvent

enum class Orientation {
    PORTRAIT,
    LANDSCAPE
}

/**
 * Панель с иконками ориентации, которая отслеживает позицию колонки Size
 */
class OrientationPanel(private val table: JTable) : JPanel() {
    
    private val portraitIcon = IconLoader.getIcon("/icons/mobile.svg", OrientationPanel::class.java)
    private val portraitInactiveIcon = IconLoader.getIcon("/icons/mobile_inactive.svg", OrientationPanel::class.java)
    private val landscapeIcon = IconLoader.getIcon("/icons/mobile_landscape.svg", OrientationPanel::class.java)
    private val landscapeInactiveIcon = IconLoader.getIcon("/icons/mobile_landscape_inactive.svg", OrientationPanel::class.java)
    
    private var currentOrientation = Orientation.PORTRAIT
    private var orientationChangeListener: ((Orientation) -> Unit)? = null
    private var isListenerReady = false
    
    private val portraitButton = JButton(portraitIcon).apply {
        preferredSize = Dimension(JBUIScale.scale(24), JBUIScale.scale(24))
        minimumSize = preferredSize
        maximumSize = preferredSize
        toolTipText = "Switch all to portrait orientation"
        
        // Убираем все визуальные эффекты чекбокса
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        
        // Добавляем эффект при наведении
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                cursor = Cursor.getDefaultCursor()
            }
        })
        
        addActionListener { 
            PluginLogger.debug(LogCategory.UI_EVENTS, "Portrait orientation clicked")
            switchToPortrait()
        }
    }
    
    private val landscapeButton = JButton(landscapeIcon).apply {
        preferredSize = Dimension(JBUIScale.scale(24), JBUIScale.scale(24))
        minimumSize = preferredSize
        maximumSize = preferredSize
        toolTipText = "Switch all to landscape orientation"
        
        // Убираем все визуальные эффекты чекбокса
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        
        // Добавляем эффект при наведении
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                cursor = Cursor.getDefaultCursor()
            }
        })
        
        addActionListener { 
            PluginLogger.debug(LogCategory.UI_EVENTS, "Landscape orientation clicked")
            switchToLandscape()
        }
    }
    
    init {
        layout = null // Используем абсолютное позиционирование
        preferredSize = Dimension(0, JBUIScale.scale(32))
        minimumSize = Dimension(0, JBUIScale.scale(32))
        maximumSize = Dimension(Integer.MAX_VALUE, JBUIScale.scale(32))
        border = JBUI.Borders.empty(4, 0)
        
        add(portraitButton)
        add(landscapeButton)
        
        // Устанавливаем начальное состояние
        updateButtonStates()
        
        // Инициализация позиции и слушателей
        SwingUtilities.invokeLater {
            updateIconsPosition()
            setupColumnListener()
        }
    }
    
    private fun setupColumnListener() {
        table.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnAdded(e: TableColumnModelEvent?) = updateIconsPosition()
            override fun columnRemoved(e: TableColumnModelEvent?) = updateIconsPosition()
            override fun columnMoved(e: TableColumnModelEvent?) = updateIconsPosition()
            override fun columnMarginChanged(e: ChangeEvent?) = updateIconsPosition()
            override fun columnSelectionChanged(e: ListSelectionEvent?) {}
        })
    }
    
    private fun updateIconsPosition() {
        SwingUtilities.invokeLater {
            if (table.columnModel.columnCount > 3) {
                // Находим колонку Size (индекс 3)
                val sizeColumnIndex = 3
                var sizeColumnX = 0
                
                // Вычисляем X позицию колонки Size
                for (i in 0 until sizeColumnIndex) {
                    sizeColumnX += table.columnModel.getColumn(i).width
                }
                
                val sizeColumn = table.columnModel.getColumn(sizeColumnIndex)
                val sizeColumnWidth = sizeColumn.width
                
                // Выравниваем иконки по левому краю колонки Size
                val leftMargin = JBUIScale.scale(8) // Небольшой отступ от левого края
                val x = sizeColumnX + leftMargin
                val y = JBUIScale.scale(4)
                
                // Позиционируем кнопки
                portraitButton.setBounds(x, y, JBUIScale.scale(24), JBUIScale.scale(24))
                landscapeButton.setBounds(x + JBUIScale.scale(28), y, JBUIScale.scale(24), JBUIScale.scale(24))
                
                PluginLogger.debug(LogCategory.UI_EVENTS, 
                    "Icons positioned: sizeColumnX=$sizeColumnX, width=$sizeColumnWidth, x=$x")
                
                repaint()
            }
        }
    }
    
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        // Для отладки - рисуем границы панели (закомментировано)
        // g.color = Color.RED.withAlpha(30)
        // g.fillRect(0, 0, width, height)
    }
    
    private fun updateButtonStates() {
        when (currentOrientation) {
            Orientation.PORTRAIT -> {
                portraitButton.icon = portraitIcon
                landscapeButton.icon = landscapeInactiveIcon
            }
            Orientation.LANDSCAPE -> {
                portraitButton.icon = portraitInactiveIcon
                landscapeButton.icon = landscapeIcon
            }
        }
    }
    
    fun resetToPortrait() {
        if (currentOrientation == Orientation.LANDSCAPE) {
            switchToPortrait()
        }
    }
    
    private fun switchToPortrait() {
        if (currentOrientation == Orientation.PORTRAIT) return
        
        println("ADB_DEBUG: switchToPortrait called!")
        println("ADB_DEBUG: Stack trace:")
        Thread.currentThread().stackTrace.take(10).forEach { 
            println("ADB_DEBUG:   at $it")
        }
        
        // Пропускаем восстановление выделения после смены ориентации
        SelectionTracker.setSkipNextRestore()
        
        currentOrientation = Orientation.PORTRAIT
        updateButtonStates()
        updateTableResolutions(true)
        
        // Уведомляем слушателей о смене ориентации только если слушатель готов
        if (isListenerReady) {
            orientationChangeListener?.invoke(Orientation.PORTRAIT)
        } else {
            println("ADB_DEBUG: Listener not ready yet, skipping notification")
        }
        
        PluginLogger.debug(LogCategory.UI_EVENTS, "Switched to portrait orientation")
    }
    
    private fun switchToLandscape() {
        if (currentOrientation == Orientation.LANDSCAPE) return
        
        // Пропускаем восстановление выделения после смены ориентации
        SelectionTracker.setSkipNextRestore()
        
        currentOrientation = Orientation.LANDSCAPE
        updateButtonStates()
        updateTableResolutions(false)
        
        // Уведомляем слушателей о смене ориентации только если слушатель готов
        if (isListenerReady) {
            orientationChangeListener?.invoke(Orientation.LANDSCAPE)
        } else {
            println("ADB_DEBUG: Listener not ready yet, skipping notification")
        }
        
        PluginLogger.debug(LogCategory.UI_EVENTS, "Switched to landscape orientation")
    }
    
    private fun updateTableResolutions(toPortrait: Boolean) {
        val model = table.model as? DevicePresetTableModel ?: return
        
        println("ADB_DEBUG: updateTableResolutions called - toPortrait: $toPortrait")
        println("ADB_DEBUG: Stack trace:")
        Thread.currentThread().stackTrace.take(10).forEach { 
            println("ADB_DEBUG:   at $it")
        }
        
        // Отключаем события таблицы, чтобы избежать множественных обновлений
        table.isEnabled = false
        
        // Устанавливаем флаг, что происходит изменение ориентации
        model.setOrientationChanging(true)
        
        try {
            for (row in 0 until model.rowCount) {
                // Пропускаем строку с кнопкой добавления
                val firstColumn = model.getValueAt(row, 0) as? String
                if (firstColumn == "+") continue
                
                val currentSize = model.getValueAt(row, 3) as? String ?: continue
                if (currentSize.isBlank()) continue
                
                // Парсим разрешение
                val parts = currentSize.split("x", "×")
                if (parts.size != 2) continue
                
                val width = parts[0].trim().toIntOrNull() ?: continue
                val height = parts[1].trim().toIntOrNull() ?: continue
                
                // Определяем нужно ли менять ориентацию
                val isCurrentlyPortrait = height > width
                val needsChange = if (toPortrait) !isCurrentlyPortrait else isCurrentlyPortrait
                
                if (needsChange) {
                    // Меняем местами ширину и высоту
                    val newSize = "${height}x${width}"
                    model.setValueAt(newSize, row, 3)
                    
                    PluginLogger.debug(LogCategory.UI_EVENTS, 
                        "Changed resolution at row $row from $currentSize to $newSize")
                }
            }
        } finally {
            // Сбрасываем флаг изменения ориентации
            model.setOrientationChanging(false)
            table.isEnabled = true
        }
        
        // Обновляем отображение таблицы
        table.repaint()
    }
    
    /**
     * Возвращает текущую ориентацию
     */
    fun getCurrentOrientation(): Orientation {
        return currentOrientation
    }
    
    /**
     * Устанавливает ориентацию и обновляет UI
     */
    fun setOrientation(orientation: Orientation, applyToTable: Boolean = false) {
        if (currentOrientation != orientation) {
            currentOrientation = orientation
            updateButtonStates()
            
            // Применяем к таблице если запрошено
            if (applyToTable) {
                updateTableResolutions(orientation == Orientation.PORTRAIT)
                // Уведомляем слушателей о смене ориентации только если слушатель готов
                if (isListenerReady) {
                    orientationChangeListener?.invoke(orientation)
                } else {
                    println("ADB_DEBUG: setOrientation - Listener not ready yet, skipping notification")
                }
            }
            
            PluginLogger.debug(LogCategory.UI_EVENTS, "Orientation set to: $orientation, applyToTable: $applyToTable")
        }
    }
    
    /**
     * Устанавливает слушатель изменения ориентации
     */
    fun setOrientationChangeListener(listener: (Orientation) -> Unit) {
        orientationChangeListener = listener
    }
    
    /**
     * Активирует слушатель, разрешая обработку событий изменения ориентации
     */
    fun activateListener() {
        isListenerReady = true
        println("ADB_DEBUG: OrientationPanel listener activated")
    }

}