package com.localai.toolkit.feature.developer

import androidx.annotation.StringRes
import com.localai.toolkit.R

/**
 * The developer sub-tools.
 *
 * [needsAi] is the important distinction on this screen: the deterministic utilities work
 * on every device with no model and no download, while the two explanation tools need
 * Gemini Nano. Keeping that on the enum means the list can group them honestly instead of
 * showing nine tools that might or might not work.
 */
enum class DeveloperTool(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val needsAi: Boolean,
) {
    EXPLAIN_ERROR(
        R.string.dev_explain_error_title,
        R.string.dev_explain_error_description,
        needsAi = true,
    ),
    EXPLAIN_CODE(
        R.string.dev_explain_code_title,
        R.string.dev_explain_code_description,
        needsAi = true,
    ),
    JSON_FORMATTER(
        R.string.dev_json_format_title,
        R.string.dev_json_format_description,
        needsAi = false,
    ),
    JSON_VALIDATOR(
        R.string.dev_json_validate_title,
        R.string.dev_json_validate_description,
        needsAi = false,
    ),
    BASE64(R.string.dev_base64_title, R.string.dev_base64_description, needsAi = false),
    URL_CODEC(R.string.dev_url_title, R.string.dev_url_description, needsAi = false),
    JWT_DECODER(R.string.dev_jwt_title, R.string.dev_jwt_description, needsAi = false),
    UUID_GENERATOR(R.string.dev_uuid_title, R.string.dev_uuid_description, needsAi = false),
    HASH_GENERATOR(R.string.dev_hash_title, R.string.dev_hash_description, needsAi = false),
    TIMESTAMP(R.string.dev_timestamp_title, R.string.dev_timestamp_description, needsAi = false),
}

/** Language shortcuts for Explain Error, so the model gets useful context for free. */
enum class ErrorLanguage(@param:StringRes val labelRes: Int, val promptName: String) {
    ANDROID(R.string.dev_lang_android, "Android"),
    KOTLIN(R.string.dev_lang_kotlin, "Kotlin"),
    GRADLE(R.string.dev_lang_gradle, "Gradle"),
    JAVA(R.string.dev_lang_java, "Java"),
    PYTHON(R.string.dev_lang_python, "Python"),
    JAVASCRIPT(R.string.dev_lang_javascript, "JavaScript"),
    GENERIC(R.string.dev_lang_generic, "any language"),
}
