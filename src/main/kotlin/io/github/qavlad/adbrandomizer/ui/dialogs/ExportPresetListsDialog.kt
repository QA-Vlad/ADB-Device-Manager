package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import java.awt.Component
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * Диалог для выбора списков пресетов для экспорта
 */
class ExportPresetListsDialog(
    private val listNames: Array<String>
) : DialogWrapper(true) {
    
    private val checkBoxes = mutableListOf<JBCheckBox>()
    private var currentFocusIndex = -1
    private var keyEventDispatcher: java.awt.KeyEventDispatcher? = null
    private lateinit var okButton: JButton
    private lateinit var cancelButton: JButton
    
    init {
        title = "Export Preset Lists"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            
            // Заголовок и кнопки Select All / Deselect All в одной строке
            val headerPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JLabel("Select preset lists to export:"))
                add(Box.createHorizontalGlue())
                add(JButton("Select All").apply {
                    addActionListener {
                        checkBoxes.forEach { it.isSelected = true }
                    }
                })
                add(Box.createHorizontalStrut(5))
                add(JButton("Deselect All").apply {
                    addActionListener {
                        checkBoxes.forEach { it.isSelected = false }
                    }
                })
            }
            add(headerPanel)
            add(Box.createVerticalStrut(10))
            
            listNames.forEach { name ->
                val checkBox = JBCheckBox(name, true).apply {
                    isFocusable = true
                    
                    // Сохраняем ссылку на чекбокс для использования в KeyListener
                    val checkBoxRef = this
                    
                    // Добавляем обработку клавиш для каждого чекбокса
                    addKeyListener(object : KeyAdapter() {
                        override fun keyPressed(e: KeyEvent) {
                            println("ADB_DEBUG: ExportDialog - CheckBox key pressed: ${e.keyCode}")
                            when (e.keyCode) {
                                KeyEvent.VK_UP -> {
                                    val currentIndex = checkBoxes.indexOf(checkBoxRef)
                                    
                                    // Обновляем currentFocusIndex при навигации
                                    if (currentIndex >= 0) {
                                        currentFocusIndex = currentIndex
                                    }
                                    
                                    navigateCheckBoxes(false)
                                    e.consume()
                                }
                                KeyEvent.VK_DOWN -> {
                                    val currentIndex = checkBoxes.indexOf(checkBoxRef)
                                    
                                    // Обновляем currentFocusIndex при навигации
                                    if (currentIndex >= 0) {
                                        currentFocusIndex = currentIndex
                                    }
                                    
                                    navigateCheckBoxes(true)
                                    e.consume()
                                }
                                KeyEvent.VK_LEFT -> {
                                    val currentIndex = checkBoxes.indexOf(checkBoxRef)
                                    
                                    // Обновляем currentFocusIndex при навигации
                                    if (currentIndex >= 0) {
                                        currentFocusIndex = currentIndex
                                    }
                                    
                                    navigateCheckBoxes(false)
                                    e.consume()
                                }
                                KeyEvent.VK_RIGHT -> {
                                    val currentIndex = checkBoxes.indexOf(checkBoxRef)
                                    
                                    // Обновляем currentFocusIndex при навигации
                                    if (currentIndex >= 0) {
                                        currentFocusIndex = currentIndex
                                    }
                                    
                                    navigateCheckBoxes(true)
                                    e.consume()
                                }
                                KeyEvent.VK_SPACE, KeyEvent.VK_ENTER -> {
                                    isSelected = !isSelected
                                    e.consume()
                                }
                            }
                        }
                    })
                }
                checkBoxes.add(checkBox)
                add(checkBox)
            }
        }
        
        // Устанавливаем фокус на первый чекбокс после инициализации
        SwingUtilities.invokeLater {
            if (checkBoxes.isNotEmpty()) {
                checkBoxes[0].requestFocusInWindow()
                currentFocusIndex = 0
            }
        }
        
        return panel
    }
    
    private fun navigateCheckBoxes(forward: Boolean) {
        if (checkBoxes.isEmpty()) return
        
        val oldIndex = currentFocusIndex
        
        if (forward && currentFocusIndex == checkBoxes.size - 1) {
            // Переходим с последнего чекбокса на кнопки
            currentFocusIndex = -1
            okButton.requestFocusInWindow()
            println("ADB_DEBUG: Navigated from last checkbox to OK button")
            return
        }
        
        currentFocusIndex = if (forward) {
            (currentFocusIndex + 1) % checkBoxes.size
        } else {
            if (currentFocusIndex <= 0) checkBoxes.size - 1 else currentFocusIndex - 1
        }
        
        if (currentFocusIndex in checkBoxes.indices) {
            checkBoxes[currentFocusIndex].requestFocusInWindow()
            println("ADB_DEBUG: Navigated from checkbox $oldIndex to $currentFocusIndex")
        }
    }
    
    /**
     * Возвращает выбранные списки
     */
    fun getSelectedLists(): List<String> {
        return checkBoxes
            .filter { it.isSelected }
            .map { it.text }
    }
    
    override fun createSouthPanel(): JComponent {
        // Создаём свою панель с кнопками
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT))
        
        val okButton = JButton("OK").apply {
            addActionListener { doOKAction() }
            
            // Отключаем стандартную навигацию
            isFocusTraversalPolicyProvider = false
            setFocusTraversalKeysEnabled(false)
            
            // Простой подход - обрабатываем все события клавиш
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    println("ADB_DEBUG: Custom OK button key: ${e.keyCode}")
                    when (e.keyCode) {
                        KeyEvent.VK_LEFT, KeyEvent.VK_UP -> {
                            // С OK переходим на последний чекбокс
                            if (checkBoxes.isNotEmpty()) {
                                currentFocusIndex = checkBoxes.size - 1
                                checkBoxes[currentFocusIndex].requestFocusInWindow()
                                println("ADB_DEBUG: Navigated from OK to last checkbox")
                            }
                            e.consume()
                        }
                        KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN -> {
                            cancelButton.requestFocusInWindow()
                            e.consume()
                        }
                        KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                            doOKAction()
                            e.consume()
                        }
                    }
                }
            })
        }
        
        val cancelButton = JButton("Cancel").apply {
            addActionListener { doCancelAction() }
            
            // Отключаем стандартную навигацию
            isFocusTraversalPolicyProvider = false
            setFocusTraversalKeysEnabled(false)
            
            // Простой подход - обрабатываем все события клавиш
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    println("ADB_DEBUG: Custom Cancel button key: ${e.keyCode}")
                    when (e.keyCode) {
                        KeyEvent.VK_LEFT, KeyEvent.VK_UP -> {
                            okButton.requestFocusInWindow()
                            e.consume()
                        }
                        KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN -> {
                            // С Cancel нельзя никуда перейти вправо/вниз
                            e.consume()
                        }
                        KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                            doCancelAction()
                            e.consume()
                        }
                    }
                }
            })
        }
        
        // Сохраняем ссылки на кнопки
        this.okButton = okButton
        this.cancelButton = cancelButton
        
        panel.add(okButton)
        panel.add(cancelButton)
        
        // Устанавливаем OK как кнопку по умолчанию
        rootPane?.defaultButton = okButton
        
        return panel
    }
    
    override fun getPreferredFocusedComponent(): JComponent? {
        return if (checkBoxes.isNotEmpty()) checkBoxes[0] else null
    }
    
    override fun init() {
        super.init()
        
        println("ADB_DEBUG: ExportPresetListsDialog.init() called")
        
        // Устанавливаем флаг в системные свойства для KeyboardHandler
        System.setProperty("adbrandomizer.exportDialogOpen", "true")
        
        // Костыль для обработки стрелки влево на кнопке Cancel
        // Добавляем KeyEventDispatcher (обрабатывает ДО других обработчиков)
        val keyDispatcher = java.awt.KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_LEFT && isVisible) {
                val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                
                // Если фокус на кнопке Cancel в нашем диалоге
                if (focusOwner is JButton && focusOwner.text == "Cancel" && 
                    SwingUtilities.isDescendingFrom(focusOwner, rootPane)) {
                    println("ADB_DEBUG: WORKAROUND - Converting LEFT to UP for Cancel button")
                    
                    // Создаём и отправляем событие стрелки вверх
                    val upEvent = KeyEvent(
                        focusOwner,
                        KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),
                        0,
                        KeyEvent.VK_UP,
                        KeyEvent.CHAR_UNDEFINED
                    )
                    SwingUtilities.invokeLater {
                        focusOwner.dispatchEvent(upEvent)
                    }
                    
                    return@KeyEventDispatcher true // Блокируем оригинальное событие LEFT
                }
            }
            false
        }
        
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)
        
        // Сохраняем dispatcher для удаления позже
        keyEventDispatcher = keyDispatcher
        
        // Код для добавления обработчиков кнопкам больше не нужен,
        // так как мы создаём свои кнопки в createSouthPanel()
    }
    
    override fun doCancelAction() {
        System.clearProperty("adbrandomizer.exportDialogOpen")
        keyEventDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
        }
        super.doCancelAction()
    }
    
    override fun doOKAction() {
        System.clearProperty("adbrandomizer.exportDialogOpen")
        keyEventDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
        }
        super.doOKAction()
    }

    private fun findAllButtons(container: Component?): List<JButton> {
        val buttons = mutableListOf<JButton>()
        
        if (container == null) return buttons
        
        if (container is JButton && container.isVisible && container.isEnabled) {
            buttons.add(container)
        }
        
        if (container is java.awt.Container) {
            for (component in container.components) {
                buttons.addAll(findAllButtons(component))
            }
        }
        
        // Возвращаем кнопки в правильном порядке (OK, Cancel)
        return buttons.sortedBy { button ->
            when (button.text) {
                "OK" -> 0
                "Cancel" -> 1
                else -> 2
            }
        }
    }
}