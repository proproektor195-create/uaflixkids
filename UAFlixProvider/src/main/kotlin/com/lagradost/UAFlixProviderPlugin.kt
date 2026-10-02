package com.lagradost

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.lagradost.cloudstream3.AcraApplication.Companion.getActivity
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

        openSettings = settings@{

            val activity =
                context.getActivity()
                    ?: return@settings

            val editText = EditText(activity).apply {

                inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE

                minLines = 12

                hint = """
Назва | URL

Приклади:

Anime Kids | https://www.youtube.com/@animekids

Мультфільми | https://www.youtube.com/playlist?list=PLXXXXXXXX

Cartoon UA | https://www.youtube.com/channel/UCXXXXXXXX
                """.trimIndent()

                setText(
                    YouTubeSettingsStore.raw(context)
                )
            }

            val container =
                LinearLayout(activity).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    addView(editText)
                }

            val scroll =
                ScrollView(activity).apply {

                    addView(container)
                }

            AlertDialog.Builder(activity)
                .setTitle("YouTube канали")
                .setMessage(
                    """
Додайте власні канали або плейлисти.

Кожен запис в окремому рядку:

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
                .setNegativeButton("Скасувати", null)
                .show()
        }
    }
}
