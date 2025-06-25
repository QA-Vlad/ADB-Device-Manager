# ADB Screen Randomizer

> 🔧 **IntelliJ IDEA/Android Studio plugin created with love for mobile QA and developers**

⚠️ **Project is in early development stage** — functionality is being actively improved

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-In%20Development-red.svg)]()

## 📱 About the project

**ADB Screen Randomizer** is an IntelliJ IDEA plugin designed to simplify the process of testing mobile applications on various screen resolutions and DPI settings. The plugin allows you to quickly and conveniently change screen parameters of connected Android devices directly from the IDE, significantly speeding up the UI adaptability testing process.

### 🎯 Project goal

Every mobile QA knows how tedious it can be to test an application on multiple different screens and pixel densities. Usually this requires:
- Switching between multiple physical devices
- Manual input of ADB commands in terminal
- Constantly remembering screen sizes of various devices

**ADB Screen Randomizer** solves these problems by providing a simple and intuitive interface for instant screen parameter changes.

## ✨ Key features

### 🎲 Screen parameter randomization
- **Random size and DPI** — instant application of random screen configuration
- **Size only** — change screen resolution only
- **DPI only** — change pixel density only

### 📋 Preset management
- **Customizable device presets** — create and save configurations for popular devices
- **Preset switching** — quick switching between pre-configured settings
- **Import popular devices** — ready-made presets for common devices

### 🔄 Quick reset
- **Full reset** — return to original device parameters
- **Partial reset** — reset only size or only DPI

## 🚀 Quick start

> ⚠️ **Note:** Plugin is in development and not yet available in the official JetBrains marketplace

### Build from source

```bash
# Clone the repository
git clone https://github.com/QA-Vlad/ADB-Screen-Randomizer.git
cd ADB-Screen-Randomizer

# Open project in IntelliJ IDEA and run via green Run button
# or use gradle task 'runIde' from Gradle panel
```

### First use

1. **Connect Android device** via USB or Wi-Fi
2. **Open plugin panel** — find "ADB Randomizer" in the right sidebar
3. **Make sure device is displayed** in the connected devices list
4. **Click "RANDOM SIZE AND DPI"** to apply random configuration

## 🛠️ Usage

### Plugin interface

The plugin provides a convenient toolbar with two main sections:

#### Control panel
- `RANDOM SIZE AND DPI` — apply random screen size and DPI
- `RANDOM SIZE ONLY` — apply random screen size
- `RANDOM DPI ONLY` — apply random DPI value
- `NEXT PRESET` — switch to next preset
- `PREVIOUS PRESET` — switch to previous preset
- `Reset size and DPI to default` — reset all parameters to factory defaults
- `RESET SIZE ONLY` — reset screen size only
- `RESET DPI ONLY` — reset DPI only
- `SETTING` — open preset configuration window

#### Device panel
- Displays all connected Android devices
- Shows device name and serial number
- Automatically updates every 3 seconds

### Preset configuration

1. Click **"SETTING"** button
2. In the opened window you can:
   - **Add new preset** — "Add Preset" button
   - **Import popular devices** — "Import Common Devices" button
   - **Edit existing presets** — click on cell to edit
   - **Delete preset** — trash icon button
   - **Change order** — drag and drop presets with mouse

#### Settings format
- **Label** — preset name (e.g., "Pixel 5")
- **Size** — resolution in format `width x height` (e.g., "1080x2340")
- **DPI** — pixel density (e.g., "432")

### System requirements
- **IntelliJ IDEA** 2023.2+ or **Android Studio** Flamingo+
- **Android SDK** with configured ADB
- **Java 17+**

### Technologies used
- **Kotlin** — primary development language
- **IntelliJ Platform SDK** — for IDE integration
- **Android DDMLib** — for device interaction via ADB
- **Gson** — for settings serialization
- **Swing** — for user interface

## 🤝 Contributing

The project is open for community contributions! Since the project is in active development, your contribution is especially valuable:

### Testing
- 🧪 Test the plugin in various environments
- 📝 Report found bugs
- 💡 Suggest UX improvements

### Suggesting improvements
1. Describe desired functionality in a new Issue
2. Explain what problem it solves
3. Provide usage examples

## 📝 License

This project is distributed under the [Apache License 2.0](LICENSE). This means you can freely use, modify, and distribute the code for both personal and commercial purposes, provided you include attribution and the license text.

---

<div style="text-align: center;">

**⭐ If you find the project interesting, please star the repository!**

*Created with ❤️ for the QA community*

**Status:** 🚧 In active development

</div>