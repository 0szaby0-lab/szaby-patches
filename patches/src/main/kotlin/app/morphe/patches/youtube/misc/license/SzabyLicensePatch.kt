package app.morphe.patches.youtube.misc.license

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private val licenseSettingsResourcePatch = resourcePatch {
    dependsOn(settingsPatch)

    execute {
        PreferenceScreen.MISC.addPreferences(
            TextPreference(
                key = "szaby_license_key",
                titleKey = "szaby_license_key_title",
                summaryKey = "szaby_license_key_summary",
                inputType = InputType.TEXT,
                tag = "app.morphe.extension.shared.settings.preference.ResettableEditTextPreference"
            ),
            NonInteractivePreference(
                key = "szaby_license_status",
                titleKey = "szaby_license_status_title",
                summaryKey = "szaby_license_status_summary",
            )
        )
    }
}

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/SzabyLicensePatch;"

@Suppress("unused")
val szabyLicensePatch = bytecodePatch(
    name = "Szaby License",
    description = "Validates Szaby license key. Without a valid key, all Szaby patches are disabled.",
) {
    dependsOn(
        licenseSettingsResourcePatch,
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // The license check runs from the extension side (SzabyLicensePatch.java)
        // which is loaded by the SharedExtensionPatch on app startup.
        // Settings UI is handled by the resource patch above.
    }
}
