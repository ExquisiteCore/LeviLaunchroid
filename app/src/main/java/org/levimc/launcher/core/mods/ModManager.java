package org.levimc.launcher.core.mods;

import android.os.Environment;
import android.os.FileObserver;

import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;

import org.levimc.launcher.core.versions.GameVersion;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public class ModManager {
    private static ModManager instance;
    private File modsDir;
    private File configFile;
    private Map<String, Boolean> configMap = new HashMap<>();
    private FileObserver modDirObserver;
    private GameVersion currentVersion;
    private final MutableLiveData<Void> modsChangedLiveData = new MutableLiveData<>();
    private final Gson gson = new Gson();

    private ModManager() {
        modsDir = currentVersion != null ? currentVersion.modsDir :
                new File(Environment.getExternalStorageDirectory(), "games/org.levimc/mods");
        configFile = new File(modsDir, "mods_config.json");
        if (!modsDir.exists()) modsDir.mkdirs();
        loadConfig();
        initFileObserver();
    }

    public static synchronized ModManager getInstance() {
        if (instance == null) {
            instance = new ModManager();
        }
        return instance;
    }

    public synchronized void setCurrentVersion(GameVersion version) {
        if (Objects.equals(this.currentVersion, version)) return;
        stopFileObserver();

        this.currentVersion = version;
        if (currentVersion != null) {
            this.modsDir = currentVersion.modsDir;
            if (!modsDir.exists()) modsDir.mkdirs();
            this.configFile = new File(modsDir, "mods_config.json");
            loadConfig();
            initFileObserver();
        } else {
            modsDir = null;
            configFile = null;
            configMap = new HashMap<>();
        }
        postModChanged();
    }

    public List<Mod> getMods() {
        if (currentVersion == null || modsDir == null) return new ArrayList<>();
        File[] files = modsDir.listFiles((dir, name) -> name.endsWith(".so"));
        List<Mod> mods = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                String fileName = file.getName();
                boolean enabled = Boolean.TRUE.equals(configMap.getOrDefault(fileName, true));
                mods.add(new Mod(fileName, enabled));
            }
        }
        return mods;
    }

    public synchronized void setModEnabled(String fileName, boolean enabled) {
        if (currentVersion == null || modsDir == null) return;
        if (!fileName.endsWith(".so")) fileName += ".so";
        configMap.put(fileName, enabled);
        saveConfig();
        postModChanged();
    }

    private void loadConfig() {
        configMap = new HashMap<>();
        if (!configFile.exists()) {
            File[] files = modsDir.listFiles((dir, name) -> name.endsWith(".so"));
            if (files != null) {
                for (File file : files) {
                    configMap.put(file.getName(), true);
                }
            }
            saveConfig();
            return;
        }
        try (FileReader reader = new FileReader(configFile)) {
            Map<String, Boolean> map = gson.fromJson(reader, Map.class);
            if (map != null) configMap.putAll(map);
        } catch (Exception ignored) {}
    }

    private void saveConfig() {
        if (configFile == null) return;
        try (FileWriter writer = new FileWriter(configFile)) {
            gson.toJson(configMap, writer);
        } catch (Exception ignored) { }
    }

    private synchronized void initFileObserver() {
        if (modsDir == null) return;
        modDirObserver = new FileObserver(modsDir.getAbsolutePath(), FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_FROM | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                postModChanged();
            }
        };
        modDirObserver.startWatching();
    }

    private void stopFileObserver() {
        if (modDirObserver != null) {
            try { modDirObserver.stopWatching(); } catch (Exception ignored) {}
            modDirObserver = null;
        }
    }

    private void postModChanged() {
        modsChangedLiveData.postValue(null);
    }

    public MutableLiveData<Void> getModsChangedLiveData() {
        return modsChangedLiveData;
    }
}