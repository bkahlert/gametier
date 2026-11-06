import com.bkahlert.gametier.settings.AppSettings_jvmKt;
import com.bkahlert.gametier.settings.LegacyAppSettingsKt;

public class MigrateInstalledSettings {
    public static void main(String[] args) {
        var settings = AppSettings_jvmKt.appSettingsFile(System.getenv("LOCALAPPDATA"), null);
        LegacyAppSettingsKt.migrateLegacyAppSettings(settings);
    }
}
