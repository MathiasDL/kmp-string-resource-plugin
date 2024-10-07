# String Resource extraction plugin for Kotlin Multiplatform

## Installing

- Install [LivePlugin](https://plugins.jetbrains.com/plugin/7282-liveplugin) in Android Studio
- Add a new Kotlin Plugin with the script from `/src/plugin.kts`. Note that the file needs to be called `plugin.kts` for LivePlugin to work correctly
- Configure the settings in `plugin.kts` to match your project setup:
  - Change `resourcesPackage` to match your package
  - `keyStroke` if you want a hotkey
  - Make sure `stringsXmlRelativePath` is correct

