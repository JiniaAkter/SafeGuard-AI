package com.example.safeguardaiwear.speech

import android.content.Context
import android.content.res.AssetManager
import org.vosk.Model
import java.io.File

/**
 * Loads the offline Vosk speech model. Audio never leaves the watch.
 *
 * Put the unzipped model in:  app/src/main/assets/model-en-us/
 * (am/, conf/, graph/, ivector/ ... directly inside that folder).
 *
 * On first run the model is copied from assets into app-private storage
 * (filesDir), because Vosk needs a real file path. Loading takes a few
 * seconds, so never call [load] on the main thread.
 */
object VoskModelManager {

    private const val ASSET_DIR = "model-en-us"
    private const val TARGET_DIR = "vosk-model"
    private const val MODEL_VERSION = "small-en-us-0.15" // change if you swap models

    @Volatile
    private var model: Model? = null

    @Synchronized
    fun load(context: Context): Model {
        model?.let { return it }

        val target = File(context.filesDir, TARGET_DIR)
        val marker = File(target, ".version")
        if (!marker.exists() || marker.readText() != MODEL_VERSION) {
            target.deleteRecursively()
            copyAssetDir(context.assets, ASSET_DIR, target)
            marker.writeText(MODEL_VERSION)
        }
        return Model(target.absolutePath).also { model = it }
    }

    /**
     * Checks the model is installed and loads it.
     * (vosk-android 0.3.47 can't look up single words, so this always returns
     * an empty list – tell users to pick common English words.)
     */
    fun unknownWords(context: Context, phrase: String): List<String> {
        load(context)
        return emptyList()
    }

    private fun copyAssetDir(assets: AssetManager, assetPath: String, dest: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // A file (throws FileNotFoundException if the model folder is missing).
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            dest.mkdirs()
            for (child in children) {
                copyAssetDir(assets, "$assetPath/$child", File(dest, child))
            }
        }
    }
}