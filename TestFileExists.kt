import java.io.File

fun main() {
    println("Testing file existence checking...")
    println("OS: ${System.getProperty("os.name")}")
    println("User dir: ${System.getProperty("user.dir")}")
    println()
    
    val testPaths = listOf(
        "C:\\TEST\\adb\\adb.exe",
        "C:/TEST/adb/adb.exe",
        "/mnt/c/TEST/adb/adb.exe",
        "\\\\wsl.localhost\\Ubuntu\\mnt\\c\\TEST\\adb\\adb.exe"
    )
    
    for (path in testPaths) {
        val file = File(path)
        println("Testing: $path")
        println("  Absolute path: ${file.absolutePath}")
        try {
            println("  Canonical path: ${file.canonicalPath}")
        } catch (e: Exception) {
            println("  Canonical path error: ${e.message}")
        }
        println("  Exists: ${file.exists()}")
        println("  Is file: ${file.isFile}")
        println("  Is directory: ${file.isDirectory}")
        
        if (file.parentFile != null) {
            println("  Parent exists: ${file.parentFile.exists()}")
            println("  Parent is directory: ${file.parentFile.isDirectory}")
            
            if (file.parentFile.exists() && file.parentFile.isDirectory) {
                try {
                    val files = file.parentFile.listFiles()
                    if (files != null && files.isNotEmpty()) {
                        println("  Files in parent directory:")
                        files.filter { it.name.contains("adb", ignoreCase = true) }.forEach { f ->
                            println("    - ${f.name} (exists: ${f.exists()}, size: ${if (f.exists()) f.length() else "N/A"})")
                        }
                    }
                } catch (e: Exception) {
                    println("  Error listing parent files: ${e.message}")
                }
            }
        }
        println()
    }
}