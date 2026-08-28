package xiaoming.lanlock;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PasswordManager {
    private static final Gson GSON = new Gson();
    private static final String PASSWORD_FILE = "lanlock_passwords.json";
    
    private final Path savePath;
    private Map<String, String> passwords = new HashMap<>();
    private Map<String, Boolean> registered = new HashMap<>();
    
    public PasswordManager(MinecraftServer server) {
        this.savePath = server.getSavePath(WorldSavePath.ROOT).resolve(PASSWORD_FILE);
        load();
    }
    
    public void load() {
        File file = savePath.toFile();
        if (file.exists()) {
            try (Reader reader = new FileReader(file)) {
                Map<String, Object> data = GSON.fromJson(reader, new TypeToken<Map<String, Object>>(){}.getType());
                if (data != null) {
                    passwords = (Map<String, String>) data.getOrDefault("passwords", new HashMap<>());
                    registered = (Map<String, Boolean>) data.getOrDefault("registered", new HashMap<>());
                }
            } catch (Exception e) {
                LanLock.LOGGER.error("加载密码文件失败", e);
            }
        }
    }
    
    public void save() {
        try {
            savePath.getParent().toFile().mkdirs();
            Map<String, Object> data = new HashMap<>();
            data.put("passwords", passwords);
            data.put("registered", registered);
            
            try (Writer writer = new FileWriter(savePath.toFile())) {
                GSON.toJson(data, writer);
            }
        } catch (Exception e) {
            LanLock.LOGGER.error("保存密码文件失败", e);
        }
    }
    
    public boolean isRegistered(UUID uuid) {
        return registered.getOrDefault(uuid.toString(), false);
    }
    
    public boolean verifyPassword(UUID uuid, String password) {
        String stored = passwords.get(uuid.toString());
        return stored != null && stored.equals(password);
    }
    
    public void register(UUID uuid, String password) {
        passwords.put(uuid.toString(), password);
        registered.put(uuid.toString(), true);
        save();
    }
}