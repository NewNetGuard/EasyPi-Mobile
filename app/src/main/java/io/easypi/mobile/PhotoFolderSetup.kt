package io.easypi.mobile

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout

object PhotoFolderSetup {
    fun show(activity: Activity, required: Boolean, onSaved: () -> Unit = {}) {
        val link = EasyPiLinkStore(activity).linkedEasyPi() ?: return
        val input = EditText(activity).apply {
            hint = "For example, Sergey"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setText(link.photoFolder.orEmpty())
            setSelection(text.length)
        }
        val backupVideos = CheckBox(activity).apply {
            text = "Back up videos to a separate Videos folder"
            isChecked = link.backupVideos
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (24 * resources.displayMetrics.density).toInt()
            setPadding(horizontal, 0, horizontal, 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(backupVideos, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle("Name your media folder")
            .setMessage("Photos stay in this folder. If enabled, videos are stored separately in its Videos folder. You can turn video backup on or off later in Gallery or Settings.")
            .setView(container)
            .setPositiveButton("Save", null)
            .setCancelable(!required)
        if (!required) builder.setNegativeButton("Cancel", null)
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(!required)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    input.error = "Enter a folder name"
                    return@setOnClickListener
                }
                val includeVideos = backupVideos.isChecked
                input.isEnabled = false
                backupVideos.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val result = runCatching {
                        MobileApiClient.setPhotoFolder(link, name, includeVideos)
                    }
                    activity.runOnUiThread {
                        result.onSuccess { settings ->
                            EasyPiLinkStore(activity).save(link.copy(
                                photoFolder = settings.photoFolder,
                                backupVideos = settings.backupVideos,
                            ))
                            dialog.dismiss()
                            onSaved()
                        }.onFailure { error ->
                            input.isEnabled = true
                            backupVideos.isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            input.error = error.message ?: "Could not create the photo folder"
                        }
                    }
                }.start()
            }
        }
        dialog.show()
        input.requestFocus()
    }
}
