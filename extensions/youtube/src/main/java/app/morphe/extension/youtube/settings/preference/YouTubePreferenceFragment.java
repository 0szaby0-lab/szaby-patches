package app.morphe.extension.youtube.settings.preference;

import android.app.Dialog;
import android.preference.PreferenceScreen;
import android.widget.Toolbar;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.GmsCoreSupportPatch;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.preference.ToolbarPreferenceFragment;
import app.morphe.extension.youtube.settings.YouTubeActivityHook;

/**
 * Preference fragment for Morphe settings.
 */
@SuppressWarnings("deprecation")
public class YouTubePreferenceFragment extends ToolbarPreferenceFragment {
    /**
     * The main PreferenceScreen used to display the current set of preferences.
     */
    private PreferenceScreen preferenceScreen;

    /**
     * Initializes the preference fragment.
     */
    @Override
    protected void initialize() {
        super.initialize();

        try {
            preferenceScreen = getPreferenceScreen();
            sortPreferenceGroups(preferenceScreen);
            setPreferenceScreenToolbar(preferenceScreen);

            // Clunky work around until preferences are custom classes that manage themselves.
            // Custom branding only works with non-root install. But the preferences must be
            // added during patched because of difficulties detecting during patching if it's
            // a root installation. So instead the non-functional preferences are removed during
            // runtime if the app is mount (root) installation.
                        if (!app.morphe.extension.youtube.patches.SzabyLicensePatch.isLicenseValid(getActivity())) {
                for (int i = preferenceScreen.getPreferenceCount() - 1; i >= 0; i--) {
                    android.preference.Preference p = preferenceScreen.getPreference(i);
                    if (!"morphe_settings_screen_11_misc".equals(p.getKey())) {
                        preferenceScreen.removePreference(p);
                    } else if (p instanceof android.preference.PreferenceScreen) {
                        android.preference.PreferenceScreen miscScreen = (android.preference.PreferenceScreen) p;
                        miscScreen.setTitle("Szaby License");
                        for (int j = miscScreen.getPreferenceCount() - 1; j >= 0; j--) {
                            android.preference.Preference p2 = miscScreen.getPreference(j);
                            if (!"szaby_license_key".equals(p2.getKey())) {
                                miscScreen.removePreference(p2);
                            }
                        }
                    }
                }
            }
            if (GmsCoreSupportPatch.isPackageNameOriginal()) {
                removePreferences(
                        SharedYouTubeSettings.CUSTOM_BRANDING_ICON.key,
                        SharedYouTubeSettings.CUSTOM_BRANDING_NAME.key);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    /**
     * Called when the fragment starts.
     */
    @Override
    public void onStart() {
        super.onStart();
        try {
            // Initialize search controller if needed.
            if (YouTubeActivityHook.searchViewController != null) {
                // Trigger search data collection after fragment is ready.
                YouTubeActivityHook.searchViewController.initializeSearchData();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onStart failure", ex);
        }
    }

    /**
     * Sets toolbar for all nested preference screens.
     */
    @Override
    protected void customizeToolbar(Toolbar toolbar) {
        YouTubeActivityHook.setToolbarLayoutParams(toolbar);
    }

    /**
     * Perform actions after toolbar setup.
     */
    @Override
    protected void onPostToolbarSetup(Toolbar toolbar, Dialog preferenceScreenDialog) {
        if (YouTubeActivityHook.searchViewController != null
                && YouTubeActivityHook.searchViewController.isSearchActive()) {
            toolbar.post(() -> YouTubeActivityHook.searchViewController.closeSearch());
        }
    }

    /**
     * Returns the preference screen for external access by SearchViewController.
     */
    public PreferenceScreen getPreferenceScreenForSearch() {
        return preferenceScreen;
    }
}


