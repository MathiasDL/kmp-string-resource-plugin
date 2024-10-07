# String Resource extraction plugin for Kotlin Multiplatform

Android Studio has a very handy method to [extract string resources](https://hadiyarajesh.com/how-to-extract-string-resource-in-android-studio/) to `strings.xml` files, but this does not work for shared code in Kotlin Multiplatform projects. This plugin bridges that gap by providing basic functionality to convert a String to a String Resource in the `shared`/`commonMain` module.

![til](https://raw.githubusercontent.com/MathiasDL/kmp-string-resource-plugin/main/docs/example_usage.gif)

## Installing

- Install [LivePlugin](https://plugins.jetbrains.com/plugin/7282-liveplugin) in Android Studio
- Add a new Kotlin Plugin with the script from `/src/plugin.kts`. Note that the file needs to be called `plugin.kts` for LivePlugin to work correctly
- Configure the settings in `plugin.kts` to match your project setup:
  - Change `resourcesPackage` to match your package
  - `keyStroke` if you want a hotkey
  - Make sure `stringsXmlRelativePath` is correct

## Features

- Rapidly add strings to the `strings.xml` file
- Detect strings keys / values that are already present in the `strings.xml` file
- Automatically run the appropriate gradle script to prevent errors in the IDE
- Detection of simple string interpolation used in the strings (`$someVar`)
