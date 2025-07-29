import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList

fun main() {
    // Тест с обычным Gson
    val gson1 = Gson()
    val preset1 = DevicePreset("Test", "1080x1920", "420")
    println("Original ID: ${preset1.id}")
    
    val json1 = gson1.toJson(preset1)
    println("Default Gson JSON: $json1")
    
    // Тест с настроенным Gson
    val gson2 = GsonBuilder()
        .serializeNulls()
        .setPrettyPrinting()
        .create()
    
    val json2 = gson2.toJson(preset1)
    println("\nConfigured Gson JSON: $json2")
    
    // Тест с PresetList
    val presetList = PresetList(
        name = "Test List",
        presets = mutableListOf(preset1)
    )
    
    val listJson = gson2.toJson(presetList)
    println("\nPresetList JSON: $listJson")
}