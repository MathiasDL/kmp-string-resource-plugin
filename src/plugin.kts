package src

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.ui.components.JBTextField
import liveplugin.PluginUtil.show
import liveplugin.registerEditorAction
import liveplugin.registerIntention
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.beans.PropertyChangeEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Document
import javax.swing.text.JTextComponent
import javax.xml.parsers.DocumentBuilderFactory

// Change these settings to match your project
val keyStroke = "ctrl alt R"
val stringsXmlRelativePath = "/composeApp/src/commonMain/composeResources/values/strings.xml"
val resourcesPackage = "com.sampleapp.composeapp.generated.resources"
val gradleTaskToRefreshResources = "generateResourceAccessorsForCommonMain"
val newLineCharacter = "\n" // May need to be \r\n for Windows

fun convertSelectedStringToKey(displayString: String): String {
    return displayString.lowercase()
        .replace("[^a-z0-9]".toRegex(), "_") // Replace all non-alphanumerics with _
        .replace("_+".toRegex(), "_") // replace double _'s with single _
        .trim('_') // trim _'s at start and end
}

val stringsFile = File(project!!.basePath + stringsXmlRelativePath)
val stringsXmlWriter = StringsXmlWriter(stringsFile, project!!)

registerIntention(ExtractIntention())

inner class ExtractIntention : PsiElementBaseIntentionAction() {
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        if (!element.isInKotlinFile()) return false

        var elementToValidate: PsiElement = element
        for (i in 1..5) {
            if (elementToValidate.elementType.toString().equals("STRING_TEMPLATE")) {
                return true
            }

            elementToValidate = elementToValidate.parent ?: return false
        }

        return false
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        if (editor != null) {
            extractStringResource(project, editor, editor.caretModel.currentCaret)
        }
    }

    override fun getText() = "Extract as String Resource"
    override fun getFamilyName() = "Extract as String Resource"
}

// Also register as editor action, so we can remember the keyStroke after IDE restart
registerEditorAction(
    id = "Extract as String Resource",
    keyStroke = keyStroke,
) { editor, caret, _ ->
    extractStringResource(project ?: return@registerEditorAction, editor, caret)
}

fun extractStringResource(project: Project, editor: Editor, caret: Caret) {
    val lines = editor.document.text.lines()

    val lineIndex = editor.caretModel.logicalPosition.line
    val column = caret.caretModel.logicalPosition.column

    val selectedLine = lines[lineIndex]

    val stringStart = selectedLine.substring(0, column).lastIndexOf('"')
    val stringEnd = selectedLine.indexOf('"', column)

    if (stringStart == -1 || stringEnd == -1) {
        return // Cursor is not in a string
    }

    val globalStringStart = caret.offset - column + stringStart
    val globalStringEnd = caret.offset - column + stringEnd + 1

    val stringContents = selectedLine.substring(stringStart + 1, stringEnd)

    val stringVariableRegex = """\$([_a-zA-Z][_a-zA-Z0-9]*)""".toRegex()

    val variables = stringVariableRegex.findAll(stringContents)
    val variablesToAppend = variables.map { it.value.substring(1) }.toList()
    val remainingStringParts = stringContents.split(stringVariableRegex)

    val res = remainingStringParts.mapIndexed { index, part ->
        (if (index == 0) "" else "%$index\$s") + part
    }.joinToString("")

    // Parse every time, since it may have changed
    val existingXmlStrings = parseStringsXml(stringsFile)

    val dialog = ExtractStringDialog(
        existingXmlStrings = existingXmlStrings,
        selectedString = res,
        suggestedKey = convertSelectedStringToKey(remainingStringParts.joinToString("x")),
    )

    if (dialog.showAndGet()) {
        val key = dialog.getKey()

        WriteCommandAction.runWriteCommandAction(
            project
        ) {
            val variablesPart = variablesToAppend.joinToString(prefix = if (variablesToAppend.isEmpty()) "" else ", ")
            editor.document.replaceString(
                globalStringStart,
                globalStringEnd,
                "stringResource(Res.string.$key$variablesPart)"
            )

            val neededImports = listOf(
                "import org.jetbrains.compose.resources.stringResource",
                "import $resourcesPackage.$key",
                "import $resourcesPackage.Res"
            )

            addImports(neededImports, lines, editor)

            if (!dialog.existingKeyWasSelected()) {
                val value = dialog.getValue()
                stringsXmlWriter.addPairToStringsXml(key, value)

                syncGradle(project)
            }
        }
    }
}

inner class ExtractStringDialog(
    val existingXmlStrings: XmlStrings,
    selectedString: String,
    suggestedKey: String,
) : DialogWrapper(false) {

    private val keyField: JBTextField = JBTextField(suggestedKey)
    private val valueField: JBTextField = JBTextField(selectedString)
    private val infoLabel: JLabel = JLabel()

    private val existsButtons = listOf(JButton(), JButton(), JButton(), JButton())

    private var existingKeySelected: String? = null

    init {
        title = "Extract as String Resource"
        init()
    }

    fun existingKeyWasSelected(): Boolean = existingKeySelected != null

    fun getKey(): String = existingKeySelected ?: keyField.text!!
    fun getValue(): String = valueField.text!!

    override fun createNorthPanel(): JComponent {
        val textFieldDimension = Dimension(300, 40)

        val gridbag = GridBagLayout()
        val dialogPanel = JPanel(gridbag)
        dialogPanel.alignmentY = 0f
        val c = GridBagConstraints()
        c.fill = GridBagConstraints.HORIZONTAL


        dialogPanel.add(
            JLabel("Resource Key").also {
                gridbag.setConstraints(it, c.apply { gridx = 0; gridy = 0; ipadx = 10 })
            })

        keyField.addChangeListener { stringChanged() }
        dialogPanel.add(
            keyField.also {
                it.preferredSize = textFieldDimension
                gridbag.setConstraints(it, c.apply { gridx = 1; gridy = 0; ipadx = 0 })
            })

        dialogPanel.add(
            JLabel("Resource Value").also {
                gridbag.setConstraints(it, c.apply { gridx = 0; gridy = 1; ipadx = 10 })
            })

        valueField.addChangeListener { stringChanged() }
        dialogPanel.add(
            valueField.also {
                it.preferredSize = textFieldDimension
                gridbag.setConstraints(it, c.apply { gridx = 1; gridy = 1; ipadx = 0 })
            })

        dialogPanel.add(
            infoLabel.also {
                gridbag.setConstraints(it, c.apply {
                    gridx = 0
                    gridy = 2
                    gridwidth = 2
                    ipady = 50
                })
            })

        c.apply { ipady = 0; weightx = 1.0 }
        existsButtons.forEachIndexed { index, button ->
            dialogPanel.add(
                button.also {
                    gridbag.setConstraints(it, c.apply { gridx = 0; gridy = 3 + index })
                })
        }

        stringChanged()

        return dialogPanel
    }

    override fun createCenterPanel(): JComponent { // Required method
        return super.createContentPane()
    }

    private fun stringChanged() {
        val selectedString = valueField.text
        val enteredKey = keyField.text
        val existingKeys = existingXmlStrings.getExistingKeys(enteredKey, selectedString)

        infoLabel.text = if (existingKeys.isEmpty()) {
            "Number of string resources: ${existingXmlStrings.getSize()}"
        } else {
            "Match found in xml file:"
        }
        existsButtons.forEach { it.isVisible = false }

        existingKeys.forEachIndexed { index, existingKey ->
            existsButtons[index].apply {
                isVisible = true
                text = "<html><b>$existingKey</b>: ${existingXmlStrings.getValue(existingKey)}</html>"
                actionListeners.forEach { removeActionListener(it) }
                addActionListener {
                    existingKeySelected = existingKey
                    doOKAction()
                }
            }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return keyField
    }
}

fun addImports(imports: List<String>, lines: List<String>, editor: Editor) {
    imports.forEach { neededImport ->
        var position = 0

        var importsReached = false

        for (line in lines) {
            if (line.startsWith("import ")) {
                importsReached = true
                val compare = line.compareTo(neededImport)

                if (compare == 0) break // Import already exists

                if (compare > 0) {
                    editor.document.insertString(position, neededImport + newLineCharacter)
                    break
                }
            } else if (importsReached) {
                editor.document.insertString(position, neededImport + newLineCharacter)
                break
            }

            position += line.length + newLineCharacter.length
        }
    }
}

fun parseStringsXml(file: File): XmlStrings {
    val builderFactory = DocumentBuilderFactory.newInstance()
    val docBuilder = builderFactory.newDocumentBuilder()
    val doc = docBuilder.parse(file)

    val wrapper = XmlStrings()

    val xmlStrings = doc.documentElement.getElementsByTagName("string")
    for (i in 0 until xmlStrings.length) {
        val xmlString = xmlStrings.item(i)
        wrapper.addString(
            key = xmlString.attributes.getNamedItem("name").textContent,
            value = xmlString.textContent,
        )
    }

    return wrapper
}

class XmlStrings {
    private val keyValueMap: MutableMap<String, String> = mutableMapOf()
    private val valueKeyMap: MutableMap<String, MutableSet<String>> = mutableMapOf()

    fun getSize(): Int = valueKeyMap.size

    fun addString(key: String, value: String) {
        keyValueMap[key] = value

        if (valueKeyMap[value] == null) {
            valueKeyMap[value] = mutableSetOf(key)
        } else {
            valueKeyMap[value]?.add(key)
        }
    }

    fun getExistingKeys(key: String, value: String): Set<String> {
        val matchedByKey: Set<String> = if (keyValueMap.containsKey(key)) setOf(key) else setOf()
        val matchedByValue: Set<String> = valueKeyMap.get(value)?.toSet() ?: setOf()
        return matchedByKey + matchedByValue
    }

    fun getValue(key: String): String? {
        return keyValueMap.get(key)
    }
}

class StringsXmlWriter(val stringsFile: File, val project: Project) {
    fun addPairToStringsXml(key: String, value: String) {
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(stringsFile) ?: return

        val fdm: FileDocumentManager = FileDocumentManager.getInstance()
        var stringFileInEditor = EditorFactory.getInstance().allEditors
            .find { fdm.getFile(it.document)?.path == stringsFile.path }

        val stringsFileWasOpen = stringFileInEditor != null

        if (!stringsFileWasOpen) {
            stringFileInEditor = FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(project, vFile),
                false // No focus, temporary open
            )
        }

        if (stringFileInEditor != null) {
            this.addPairToStringsXmlViaEditor(key, value, stringFileInEditor)?.let { line ->
                show("Added $key to ${stringsFile.name} at line $line")
            }

            if (!stringsFileWasOpen) {
                FileEditorManager.getInstance(project).closeFile(vFile)
            }
        }
    }

    private fun addPairToStringsXmlViaEditor(key: String, value: String, editor: Editor): Int? {
        val lines = editor.document.text.lines()
        var position = 0

        val lineRegex = "[ \t]*<string +name=\"([^\"]+)\".*".toRegex()
        val resourcesEndRegex = ".*</resources>.*".toRegex()

        val valuePart = if (value.contains("[<>'\"&]".toRegex())) {
            "<![CDATA[$value]]>"
        } else {
            value
        }

        val lineToInsert = "    <string name=\"$key\">$valuePart</string>"
        var currentLine = 0

        for (line in lines) {
            val match = lineRegex.matchEntire(line)

            if (match != null) {
                val lineKey = match.groupValues[1]
                val compare = lineKey.compareTo(key)

                if (compare == 0) { // name already exists in file
                    return null
                }

                if (compare > 0) break
            } else if (resourcesEndRegex.matches(line)) {
                break // Make sure we insert before the closing tag
            }

            position += line.length + newLineCharacter.length
            currentLine++
        }

        WriteCommandAction.runWriteCommandAction(
            project,
            { editor.document.insertString(position, lineToInsert + newLineCharacter) }
        )

        return currentLine
    }
}

fun syncGradle(project: Project) {
    val gradleId = ProjectSystemId("GRADLE")
    val settings = ExternalSystemTaskExecutionSettings()
    settings.externalProjectPath = project.basePath
    settings.taskNames = listOf(gradleTaskToRefreshResources)
    settings.vmOptions = ""
    settings.externalSystemIdString = gradleId.id

    ExternalSystemUtil.runTask(
        settings,
        DefaultRunExecutor.EXECUTOR_ID,
        project,
        gradleId,
        null,
        ProgressExecutionMode.IN_BACKGROUND_ASYNC,
        false
    )
}

fun PsiElement.isInKotlinFile(): Boolean {
    val fileType = (containingFile?.fileType as? LanguageFileType) ?: return false
    return fileType.language.id.lowercase() == "kotlin"
}

/**
 * Installs a listener to receive notification when the text of this
 * [JTextComponent] is changed. Internally, it installs a [DocumentListener] on the
 * text component's [Document], and a [PropertyChangeListener] on the text component
 * to detect if the `Document` itself is replaced.
 *
 * @param changeListener a listener to receive [ChangeEvent]s when the text is changed;
 * the source object for the events will be the text component
 */
fun JTextComponent.addChangeListener(changeListener: ChangeListener) {
    val dl: DocumentListener = object : DocumentListener {
        private var lastChange = 0
        private var lastNotifiedChange = 0
        override fun insertUpdate(e: DocumentEvent) = changedUpdate(e)
        override fun removeUpdate(e: DocumentEvent) = changedUpdate(e)
        override fun changedUpdate(e: DocumentEvent) {
            lastChange++
            SwingUtilities.invokeLater {
                if (lastNotifiedChange != lastChange) {
                    lastNotifiedChange = lastChange
                    changeListener.stateChanged(ChangeEvent(this))
                }
            }
        }
    }
    addPropertyChangeListener("document") { e: PropertyChangeEvent ->
        (e.oldValue as? Document)?.removeDocumentListener(dl)
        (e.newValue as? Document)?.addDocumentListener(dl)
        dl.changedUpdate(null)
    }
    document?.addDocumentListener(dl)
}