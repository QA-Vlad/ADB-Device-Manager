# ADB Screen Randomizer

> 🔧 **An IntelliJ IDEA/Android Studio plugin created with love for mobile QA and developers**

⚠️ **This project is in an early stage of development** — functionality is being actively improved and expanded.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-In%20Development-red.svg)]()

## 📱 About the Project

**ADB Screen Randomizer** is an IntelliJ IDEA plugin designed to simplify the process of testing mobile applications on various screen resolutions and DPIs. The plugin allows you to quickly and conveniently change the screen parameters of connected Android devices directly from the IDE, significantly speeding up the UI adaptability testing process.

### 🎯 Project Goal

Every mobile QA knows how tedious it can be to test an application on multiple different screens. This usually requires:
- Switching between multiple physical devices.
- Manually entering ADB commands in the terminal.
- Constantly remembering the screen sizes of various devices.

**ADB Screen Randomizer** solves these problems by providing a simple and intuitive interface for instantly changing screen parameters and performing other useful actions.

## ✨ Key Features

### 🎲 Screen Parameter Management
- **Random Size and DPI** — instantly apply a random configuration.
- **Random Size Only** — change only the screen resolution.
- **Random DPI Only** — change only the pixel density.
- **Quick Reset** — revert to the device's original factory settings (full or partial).

### 📋 Preset Manager
- **Customizable Presets** — create and save configurations for popular devices.
- **Quick Switching** — cycle through your presets with a single button.
- **Import Popular Devices** — get ready-made presets for common devices.

### ⚡ Additional Tools
- **Connect via Wi-Fi** — enable wireless debugging mode for USB-connected devices in one click.
- **Screen Mirroring ([scrcpy integration](https://github.com/Genymobile/scrcpy))** — start streaming your device's screen to your computer directly from the IDE.

## 🚀 Quick Start

> ⚠️ **Important:** The plugin is under development and has not yet been published to the official JetBrains Marketplace.

### Build from Source

```bash
# Clone the repository
git clone [https://github.com/QA-Vlad/ADB-Screen-Randomizer.git](https://github.com/QA-Vlad/ADB-Screen-Randomizer.git)
cd ADB-Screen-Randomizer

# Open the project in IntelliJ IDEA and run it
# using the green 'Run' button or the 'runIde' Gradle task
```

### First Use

1. **Connect an Android device** via USB.
2. **Open the plugin panel** — find "ADB Randomizer" in the right sidebar.
3. **Ensure your device is displayed** in the list.
4. **Click "RANDOM SIZE AND DPI"** to apply a random configuration.

## 🛠️ Usage

### Plugin Interface

The plugin consists of two main sections:

#### Control Panel
- `RANDOM SIZE AND DPI` — apply a random size and DPI.
- `RANDOM SIZE ONLY` — apply a random size only.
- `RANDOM DPI ONLY` — apply a random DPI value only.
- `NEXT/PREVIOUS PRESET` — switch between your presets.
- `Reset size and DPI to default` — reset all parameters to factory settings.
- `RESET SIZE ONLY / RESET DPI ONLY` — reset only the size or DPI.
- `PRESETS` — open the preset configuration window.

#### Device Panel
- Displays all connected Android devices (both USB and Wi-Fi).
- Automatically refreshes every 3 seconds.
- Provides quick actions for each device:
    - **[Monitor Icon]** — start screen mirroring via `scrcpy`.
    - **Wi-Fi** — (for USB devices only) enable Wi-Fi debugging mode.

### Preset Configuration

1. Click the **"PRESETS"** button.
2. In the window that opens, you can:
   - **Add a new preset** — "Add Preset" button.
   - **Import popular devices** — "Import Common Devices" button.
   - **Edit existing ones** — simply click on a cell.
   - **Delete a preset** — the trash can icon button.
   - **Change the order** — drag and drop presets with your mouse.

### System Requirements
- **IntelliJ IDEA** 2023.2+ or **Android Studio** Iguana+
- **Android SDK** with ADB configured.
- **Java 17+**
- **Scrcpy** (optional, for the screen mirroring feature). Must be in the system's `PATH` or specified manually on first use.

### Technologies Used
- **Kotlin** — primary development language.
- **IntelliJ Platform SDK** — for IDE integration.
- **Android DDMLib** — for interacting with devices via ADB.
- **Gson** — for settings serialization.
- **Swing** — for the user interface.

## 🤝 Contributing

The project is open to community contributions! As the plugin is under active development, your input is especially valuable.

- 🧪 **Test** the plugin in various environments.
- 📝 **Report any bugs** you find by creating an Issue.
- 💡 **Suggest UX improvements** and new features.

## 📝 License

This project is distributed under the [Apache License 2.0](LICENSE). This means you can freely use, modify, and distribute the code for both personal and commercial purposes, provided you include the attribution and the license text.

---

<div style="text-align: center;">

**⭐ If you find this project interesting, please star the repository!**

*Created with ❤️ for the QA community*

**Status:** 🚧 Under active development

</div>