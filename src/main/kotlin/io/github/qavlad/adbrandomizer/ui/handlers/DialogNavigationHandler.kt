package io.github.qavlad.adbrandomizer.ui.handlers

import java.awt.Component
import java.awt.Container
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.*
import java.awt.event.ActionEvent
import io.github.qavlad.adbrandomizer.ui.components.PresetListManagerPanel

/**
 * Обработчик навигации по диалогу с помощью Tab и стрелок
 */
class DialogNavigationHandler(
    private val table: JTable,
    private val setTableFocus: () -> Unit,
    private val clearTableSelection: () -> Unit,
    private val selectFirstCell: () -> Unit
) {
    
    enum class FocusArea {
        TABLE,
        BUTTONS,
        TOP_PANEL
    }
    
    private var currentFocusArea = FocusArea.TABLE
    private var keyEventDispatcher: KeyEventDispatcher? = null
    
    fun install() {
        keyEventDispatcher = KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED) {
                when (e.keyCode) {
                    KeyEvent.VK_TAB -> handleTabNavigation(e)
                    KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT -> handleArrowNavigation(e)
                    KeyEvent.VK_ENTER -> handleEnterKey()
                    else -> false
                }
            } else {
                false
            }
        }
        
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher)
    }
    
    fun uninstall() {
        keyEventDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
        }
    }
    
    private fun handleTabNavigation(e: KeyEvent): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        
        // Определяем текущую область фокуса
        currentFocusArea = when {
            table.isFocusOwner || SwingUtilities.isDescendingFrom(focusOwner, table) -> FocusArea.TABLE
            isButtonArea(focusOwner) -> FocusArea.BUTTONS
            isTopPanelArea(focusOwner) -> FocusArea.TOP_PANEL
            else -> currentFocusArea
        }
        
        // Переключаемся между областями
        when (currentFocusArea) {
            FocusArea.TABLE -> {
                if (!table.isEditing) {
                    clearTableSelection() // Снимаем выделение с ячейки
                    focusNextArea(FocusArea.BUTTONS)
                    return true
                }
            }
            FocusArea.BUTTONS -> {
                if (e.isShiftDown) {
                    focusNextArea(FocusArea.TABLE)
                } else {
                    focusNextArea(FocusArea.TOP_PANEL)
                }
                return true
            }
            FocusArea.TOP_PANEL -> {
                if (e.isShiftDown) {
                    focusNextArea(FocusArea.BUTTONS)
                } else {
                    focusNextArea(FocusArea.TABLE)
                }
                return true
            }
        }
        
        return false
    }
    
    private fun handleArrowNavigation(e: KeyEvent): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        
        // Навигация стрелками работает только в кнопках и верхней панели
        when {
            isButtonArea(focusOwner) -> {
                return navigateInButtonArea(e.keyCode == KeyEvent.VK_RIGHT)
            }
            isTopPanelArea(focusOwner) -> {
                return navigateInTopPanel(e.keyCode == KeyEvent.VK_RIGHT)
            }
        }
        
        return false
    }
    
    private fun handleEnterKey(): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        
        // Обрабатываем Enter для элементов верхней панели
        when (focusOwner) {
            is JCheckBox -> {
                if (isTopPanelArea(focusOwner)) {
                    // Переключаем состояние чекбокса
                    focusOwner.isSelected = !focusOwner.isSelected
                    // Вызываем обработчики
                    val event = ActionEvent(focusOwner, ActionEvent.ACTION_PERFORMED, "toggle")
                    focusOwner.actionListeners.forEach { it.actionPerformed(event) }
                    println("ADB_DEBUG: Toggled checkbox '${focusOwner.text}' to ${focusOwner.isSelected}")
                    return true
                }
            }
            is JButton -> {
                if (isTopPanelArea(focusOwner)) {
                    // Нажимаем кнопку
                    focusOwner.doClick()
                    println("ADB_DEBUG: Clicked button '${focusOwner.text}'")
                    return true
                }
            }
            is JComboBox<*> -> {
                if (isTopPanelArea(focusOwner)) {
                    // Открываем выпадающий список
                    focusOwner.isPopupVisible = !focusOwner.isPopupVisible
                    println("ADB_DEBUG: Toggled combo box popup")
                    return true
                }
            }
        }
        
        return false
    }
    
    private fun focusNextArea(area: FocusArea) {
        currentFocusArea = area
        
        when (area) {
            FocusArea.TABLE -> {
                setTableFocus()
                selectFirstCell() // Выделяем первую ячейку Label
                println("ADB_DEBUG: Focus switched to TABLE area, first cell selected")
            }
            FocusArea.BUTTONS -> {
                focusFirstButton()
                println("ADB_DEBUG: Focus switched to BUTTONS area")
            }
            FocusArea.TOP_PANEL -> {
                focusFirstTopPanelComponent()
                println("ADB_DEBUG: Focus switched to TOP_PANEL area")
            }
        }
    }
    
    private fun isButtonArea(component: Component?): Boolean {
        if (component == null) return false
        
        // Проверяем, является ли компонент кнопкой Save или Cancel
        return component is JButton && (component.text == "Save" || component.text == "Cancel")
    }
    
    private fun isTopPanelArea(component: Component?): Boolean {
        if (component == null) return false
        
        // Проверяем, находится ли компонент в верхней панели
        val parent = SwingUtilities.getAncestorOfClass(PresetListManagerPanel::class.java, component)
        return parent != null
    }
    
    private fun focusFirstButton() {
        val saveButton = findButtonByText("Save")
        saveButton?.requestFocusInWindow()
    }
    
    private fun focusFirstTopPanelComponent() {
        // Находим чекбокс "Show all presets" в верхней панели
        val topPanel = findTopPanel()
        topPanel?.let {
            // Ищем чекбокс с текстом "Show all presets"
            val showAllCheckbox = findComponentRecursive(it) { component ->
                component is JCheckBox && component.text == "Show all presets"
            } as? JCheckBox
            
            if (showAllCheckbox != null) {
                showAllCheckbox.requestFocusInWindow()
                println("ADB_DEBUG: Focused on 'Show all presets' checkbox")
            } else {
                // Если не нашли чекбокс, используем первый фокусируемый компонент
                val firstFocusable = findFirstFocusableComponent(it)
                firstFocusable?.requestFocusInWindow()
                println("ADB_DEBUG: Focused on first focusable component in top panel")
            }
        }
    }
    
    private fun navigateInButtonArea(forward: Boolean): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        
        if (focusOwner is JButton) {
            when (focusOwner.text) {
                "Save" -> if (forward) {
                    findButtonByText("Cancel")?.requestFocusInWindow()
                    return true
                }
                "Cancel" -> if (!forward) {
                    findButtonByText("Save")?.requestFocusInWindow()
                    return true
                }
            }
        }
        
        return false
    }
    
    private fun navigateInTopPanel(forward: Boolean): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val topPanel = findTopPanel() ?: return false
        
        val focusableComponents = findAllFocusableComponents(topPanel)
        val currentIndex = focusableComponents.indexOf(focusOwner)
        
        if (currentIndex >= 0 && focusableComponents.size > 1) {
            val nextIndex = if (forward) {
                (currentIndex + 1) % focusableComponents.size
            } else {
                if (currentIndex == 0) focusableComponents.size - 1 else currentIndex - 1
            }
            
            val nextComponent = focusableComponents[nextIndex]
            nextComponent.requestFocusInWindow()
            
            // Выводим информацию о компоненте для отладки
            val componentInfo = when (nextComponent) {
                is JButton -> "Button: ${nextComponent.text}"
                is JCheckBox -> "CheckBox: ${nextComponent.text}"
                is JComboBox<*> -> "ComboBox"
                else -> nextComponent.javaClass.simpleName
            }
            println("ADB_DEBUG: Navigated to $componentInfo (${nextIndex + 1}/${focusableComponents.size})")
            return true
        }
        
        return false
    }
    
    private fun findTopPanel(): Container? {
        val rootPane = table.rootPane ?: return null
        return findComponentRecursive(rootPane) { component ->
            component is PresetListManagerPanel
        } as? Container
    }
    
    private fun findButtonByText(text: String): JButton? {
        val rootPane = table.rootPane ?: return null
        return findComponentRecursive(rootPane) { component ->
            component is JButton && component.text == text
        } as? JButton
    }
    
    private fun findFirstFocusableComponent(container: Container): Component? {
        for (component in container.components) {
            if (component.isFocusable && component.isEnabled) {
                return component
            }
            if (component is Container) {
                val found = findFirstFocusableComponent(component)
                if (found != null) return found
            }
        }
        return null
    }
    
    private fun findAllFocusableComponents(container: Container): List<Component> {
        val result = mutableListOf<Component>()
        
        for (component in container.components) {
            // Фильтруем только интерактивные компоненты
            if (component.isFocusable && component.isEnabled && isInteractiveComponent(component)) {
                result.add(component)
            }
            if (component is Container) {
                result.addAll(findAllFocusableComponents(component))
            }
        }
        
        return result
    }
    
    private fun isInteractiveComponent(component: Component): Boolean {
        return when (component) {
            is JButton -> true
            is JCheckBox -> true
            is JComboBox<*> -> true
            is JTextField -> true
            is JRadioButton -> true
            is JToggleButton -> true
            else -> false
        }
    }
    
    private fun findComponentRecursive(container: Container, predicate: (Component) -> Boolean): Component? {
        for (component in container.components) {
            if (predicate(component)) {
                return component
            }
            if (component is Container) {
                val found = findComponentRecursive(component, predicate)
                if (found != null) return found
            }
        }
        return null
    }
}