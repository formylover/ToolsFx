package me.leon.toolsfx.plugin

import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.text.Text
import javafx.util.StringConverter
import me.leon.ext.*
import me.leon.toolsfx.plugin.ApiConfig.resortFromConfig
import me.leon.toolsfx.plugin.net.*
import me.leon.toolsfx.plugin.net.NetHelper.parseCurl
import tornadofx.*

class ApiPostView : PluginView("ApiPost") {
    override val version = "v1.0.0.beta"
    override val date: String = "2021-10-06"
    override val author = "Leon406"
    override val description = "ApiPost"
    private val controller: ApiPostController by inject()
    private lateinit var tfUrl: TextField
    private lateinit var taReqHeaders: TextArea
    private lateinit var taReqContent: TextArea
    private lateinit var textRspStatus: Text
    private lateinit var taRspHeaders: TextArea
    private lateinit var taRspContent: TextArea
    private lateinit var table: TableView<HttpParams>
    private val methods =
        mutableListOf(
            "POST",
            "GET",
            "PUT",
            "PATCH",
            "HEAD",
            "DELETE",
            "OPTIONS",
            "TRACE",
            "CONNECT",
        )
    private val bodyType = BodyType.values().map { it.type }

    private val selectedMethod = SimpleStringProperty(methods.first())
    private val selectedBodyType = SimpleStringProperty(bodyType.first())
    private val showRspHeader = SimpleBooleanProperty(false)
    private val showReqHeader = SimpleBooleanProperty(false)
    private val showReqTable = SimpleBooleanProperty(false)
    private val isRunning = SimpleBooleanProperty(false)
    private var requestParams = FXCollections.observableArrayList(HttpParams())
    private val showTableList = listOf("json", "form-data")

    private val reqHeaders
        get() = controller.parseHeaderString(taReqHeaders.text)
    private val reqTableParams
        get() =
            requestParams
                .filter { it.isEnable && it.key.isNotEmpty() && !it.isFile }
                .associate { it.key to it.value }
                .toMutableMap()
    private val uploadParams
        get() = requestParams.firstOrNull { it.isEnable && it.key.isNotEmpty() && it.isFile }

    private val eventHandler = fileDraggedHandler {
        with(it.first()) {
            println(absolutePath)
            table.selectionModel.selectedItem.value = absolutePath
        }
    }
    override val root = vbox {
        resortFromConfig()
        prefWidth = 800.0
        spacing = 8.0
        paddingAll = 8
        hbox {
            spacing = 8.0
            alignment = Pos.CENTER_LEFT
            combobox(selectedMethod, methods)

            tfUrl =
                textfield("https://www.baidu.com") {
                    prefWidth = 400.0
                    promptText = "input your url"
                }
            button(graphic = imageview("/img/import.png")) { action { resetUi(clipboardText()) } }
            button(graphic = imageview("/img/run.png")) {
                enableWhen(!isRunning)
                action {
                    if (tfUrl.text.isEmpty() ||
                            !tfUrl.text.startsWith("http") && tfUrl.text.length < 11
                    ) {
                        primaryStage.showToast("plz input legal url")
                        return@action
                    }
                    isRunning.value = true
                    runAsync {
                        runCatching {
                            if (selectedMethod.get() == "POST")
                                when (bodyTypeMap[selectedBodyType.get()]) {
                                    BodyType.JSON, BodyType.FORM_DATA ->
                                        uploadParams?.run {
                                            controller.uploadFile(
                                                tfUrl.text,
                                                listOf(this.value.toFile()),
                                                this.key,
                                                reqTableParams as MutableMap<String, Any>,
                                                reqHeaders,
                                            )
                                        }
                                            ?: controller.post(
                                                tfUrl.text,
                                                reqTableParams as MutableMap<String, Any>,
                                                reqHeaders,
                                                bodyTypeMap[selectedBodyType.get()] == BodyType.JSON
                                            )
                                    else ->
                                        controller.postRaw(
                                            tfUrl.text,
                                            taReqContent.text,
                                            reqHeaders
                                        )
                                }
                            else
                                controller.request(
                                    tfUrl.text,
                                    selectedMethod.get(),
                                    reqTableParams as MutableMap<String, Any>,
                                    reqHeaders,
                                )
                        }
                            .onSuccess {
                                textRspStatus.text = it.statusInfo
                                taRspHeaders.text = it.headerInfo
                                taRspContent.text = it.data
                                this@ApiPostView.isRunning.value = false
                            }
                            .onFailure {
                                textRspStatus.text = it.message
                                taRspHeaders.text = ""
                                taRspContent.text = it.stacktrace()
                                this@ApiPostView.isRunning.value = false
                            }
                    }
                }
            }

            button(graphic = imageview("/img/settings.png")) {
                action { openInternalWindow<SettingsView>() }
            }
        }

        hbox {
            spacing = 8.0
            alignment = Pos.CENTER_LEFT
            label("Request:")
            hbox {
                alignment = Pos.CENTER
                togglegroup {
                    togglebutton("Body") {
                        style = "-fx-base: lightblue;"
                        action {
                            showReqHeader.value = false
                            showReqTable.value = selectedBodyType.get() in showTableList
                        }
                    }
                    togglebutton("Header") {
                        style = "-fx-base: lightblue;"
                        action {
                            showReqHeader.value = true
                            showReqTable.value = false
                        }
                    }
                }
            }

            combobox(selectedBodyType, bodyType)
            selectedBodyType.addListener { _, _, newValue ->
                println(newValue)
                showReqTable.value = (newValue as String) in showTableList
            }

            button("Pretty") {
                action {
                    visibleWhen(!showReqTable)
                    taReqContent.text = taReqContent.text.prettyJson()
                }
            }

            button("add") {
                visibleWhen(showReqTable)
                action { requestParams.add(HttpParams()) }
            }
            button("remove") {
                visibleWhen(showReqTable)
                action { requestParams.remove(table.selectionModel.selectedItem) }
            }
        }
        stackpane {
            spacing = 8.0
            prefHeight = 200.0
            taReqContent =
                textarea() {
                    promptText = "request body"
                    isWrapText = true
                    visibleWhen(!showReqHeader)
                }
            table =
                tableview(requestParams) {
                    visibleWhen(showReqTable)

                    column("key", HttpParams::key).apply {
                        cellFactory = TextFieldTableCell.forTableColumn()
                        prefWidthProperty().bind(this@tableview.widthProperty().multiply(0.3))
                    }
                    column("value", HttpParams::value).apply {
                        cellFactory = TextFieldTableCell.forTableColumn()
                        prefWidthProperty().bind(this@tableview.widthProperty().multiply(0.5))
                        onDragEntered = eventHandler
                    }
                    column("isFile", HttpParams::isFile).apply {
                        cellFactory =
                            TextFieldTableCell.forTableColumn<HttpParams, Boolean>(
                                object : StringConverter<Boolean?>() {
                                    override fun toString(obj: Boolean?): String {
                                        return obj.toString()
                                    }

                                    override fun fromString(string: String?): Boolean? {
                                        return string.toBoolean()
                                    }
                                }
                            )

                        prefWidthProperty().bind(this@tableview.widthProperty().multiply(0.1))
                    }
                    column("isEnable", HttpParams::isEnable).apply {
                        cellFactory =
                            TextFieldTableCell.forTableColumn<HttpParams, Boolean>(
                                object : StringConverter<Boolean?>() {
                                    override fun toString(obj: Boolean?): String {
                                        return obj.toString()
                                    }

                                    override fun fromString(string: String?): Boolean? {
                                        return string.toBoolean()
                                    }
                                }
                            )
                        prefWidthProperty().bind(this@tableview.widthProperty().multiply(0.1))
                    }
                    isEditable = true
                }
            taReqHeaders =
                textarea {
                    promptText = "request headers"
                    isWrapText = true
                    visibleWhen(showReqHeader)
                }
        }

        hbox {
            alignment = Pos.CENTER_LEFT
            label("Response:")
            spacing = 8.0
            hbox {
                alignment = Pos.CENTER
                togglegroup {
                    togglebutton("Body") {
                        style = "-fx-base: lightblue;"
                        action { showRspHeader.value = false }
                    }
                    togglebutton("Header") {
                        style = "-fx-base: lightblue;"
                        action { showRspHeader.value = true }
                    }
                }
            }
            button("Pretty") { action { taRspContent.text = taRspContent.text.prettyJson() } }
            button(graphic = imageview("/img/copy.png")) { action { taRspContent.text.copy() } }
        }
        stackpane {
            alignment = Pos.CENTER_RIGHT
            textRspStatus = text()
        }
        stackpane {
            prefHeight = 300.0
            spacing = 8.0
            taRspHeaders =
                textarea {
                    promptText = "response headers"
                    isEditable = false
                    isWrapText = true
                    visibleWhen(showRspHeader)
                }
            taRspContent =
                textarea {
                    promptText = "response body"
                    isEditable = false
                    isWrapText = true
                    visibleWhen(!showRspHeader)
                }
        }
        title = "ApiPost"
    }

    private fun resetUi(clipboardText: String) {
        clipboardText.parseCurl().run {
            selectedMethod.value = method
            tfUrl.text = url
            taReqHeaders.text = headers.entries.joinToString("\n") { "${it.key}: ${it.value} " }
            taReqContent.text = rawBody
            selectedBodyType.value = BodyType.RAW.type
            showReqTable.value = false
        }
    }
}