package com.lagradost

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class UAFlixProviderPlugin : Plugin() {

    override fun load(context: Context) {

        registerMainAPI(
            UAFlixProvider(
                YouTubeCatalog(context)
            )
        )

        openSettings = {

            val editText = EditText(context).apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE

                minLines = 12

                hint = """
Назва | URL

Приклади:

Малятко TV | https://www.youtube.com/@malyatkotv

Мультфільми | https://www.youtube.com/playlist?list=PLXXXXXXXX

Cartoon UA | https://www.youtube.com/channel/UCXXXXXXXX
                """.trimIndent()

                setText(
                    YouTubeSettingsStore.raw(context)
                )
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL

                val padding = 32

                setPadding(
                    padding,
                    padding,
                    padding,
                    padding
                )

                addView(editText)
            }

            val scroll = ScrollView(context).apply {
                addView(container)
            }

            AlertDialog.Builder(context)
                .setTitle("YouTube-канали")
                .setMessage(
                    """
Додайте власні YouTube-канали або плейлисти.

Кожен запис потрібно додавати з нового рядка:

Назва | URL
                    """.trimIndent()
                )
                .setView(scroll)
                .setPositiveButton("Зберегти") { _, _ ->

                    YouTubeSettingsStore.save(
                        context,
                        editText.text.toString()
                    )
                }
                .setNegativeButton(
                    "Скасувати",
                    null
                )
                .show()
        }
    }
}
