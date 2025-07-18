package sunsync;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.OfflinePlayer;

public class Main extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    
    private Map<UUID, Integer> lastInventoryHash = new HashMap<>();
    private Map<UUID, Double> lastHealth = new HashMap<>();
    private Map<UUID, Integer> lastFood = new HashMap<>();
    private Map<UUID, Integer> lastAir = new HashMap<>();
    private Map<UUID, Location> lastSpawnLocation = new HashMap<>();
    private boolean syncing = false;
    private boolean deathSyncing = false;
    private String lastDeathMessage = "";
    private Player firstDeadPlayer = null;
    private File sharedStateFile = new File(getDataFolder(), "shared_state.yml");
    private File settingsFile = new File(getDataFolder(), "settings.yml");
    
    // Настройки
    private boolean syncEnabled = true;
    private boolean protectEnabled = true;
    private Set<UUID> protectedPlayers = new HashSet<>();
    private Map<String, UUID> occupiedContainers = new HashMap<>(); // Занятые контейнеры: координаты -> UUID игрока

    private Map<UUID, Integer> deathCounts = new HashMap<>();

    private boolean statsEnabled = true;
    private File deathsFile;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getDataFolder().mkdirs();
        deathsFile = new File(getDataFolder(), "deaths.yml");
        loadSettings();
        loadDeathCounts();
        getCommand("sunsync").setExecutor(this);
        getCommand("sunsync").setTabCompleter(this);
        
        if (syncEnabled) {
            new SyncTask().runTaskTimer(this, 0, 1); // каждый тик
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new DeathPlaceholder().register();
            getLogger().info("PlaceholderAPI integration enabled");
        }
    }

    @Override
    public void onDisable() {
        saveSettings();
        saveDeathCounts();
    }

    private void loadSettings() {
        if (!settingsFile.exists()) {
            saveDefaultSettings();
            return;
        }
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(settingsFile);
        syncEnabled = config.getBoolean("sync_enabled", true);
        protectEnabled = config.getBoolean("protect_enabled", true);
        statsEnabled = config.getBoolean("stats_enabled", true); // Загружаем statsEnabled
        
        List<String> protectedList = config.getStringList("protected_players");
        protectedPlayers.clear();
        for (String uuidStr : protectedList) {
            try {
                protectedPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Неверный UUID в защищенных игроках: " + uuidStr);
            }
        }
        
        getLogger().info("Настройки загружены: sync=" + syncEnabled + ", protect=" + protectEnabled + ", protected_count=" + protectedPlayers.size());
    }

    private void saveSettings() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("sync_enabled", syncEnabled);
        config.set("protect_enabled", protectEnabled);
        config.set("stats_enabled", statsEnabled); // Сохраняем statsEnabled
        
        List<String> protectedList = protectedPlayers.stream()
                .map(UUID::toString)
                .collect(Collectors.toList());
        config.set("protected_players", protectedList);
        
        try {
            config.save(settingsFile);
        } catch (IOException e) {
            getLogger().severe("Не удалось сохранить настройки: " + e.getMessage());
        }
    }

    private void saveDefaultSettings() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("sync_enabled", true);
        config.set("protect_enabled", true);
        config.set("stats_enabled", true); // Стандартный statsEnabled
        config.set("protected_players", new ArrayList<String>());
        
        try {
            config.save(settingsFile);
        } catch (IOException e) {
            getLogger().severe("Не удалось сохранить стандартные настройки: " + e.getMessage());
        }
    }

    private void loadDeathCounts() {
        if (!deathsFile.exists()) {
            saveDeathCounts();
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(deathsFile);
        if (!config.contains("death_counts")) return;
        
        for (String key : config.getConfigurationSection("death_counts").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                int deaths = config.getInt("death_counts." + key);
                deathCounts.put(uuid, deaths);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Неверный UUID в death_counts: " + key);
            }
        }
        getLogger().info("Загружено " + deathCounts.size() + " счетчиков смертей");
    }

    private void saveDeathCounts() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(deathsFile);
        
        for (Map.Entry<UUID, Integer> entry : deathCounts.entrySet()) {
            config.set("death_counts." + entry.getKey().toString(), entry.getValue());
        }
        
        try {
            config.save(deathsFile);
        } catch (IOException e) {
            getLogger().severe("Не удалось сохранить счетчики смертей: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("sunsync")) return false;
        
        if (!sender.hasPermission("sunsync.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав для использования этой команды!");
            return true;
        }
        
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "SunSync v" + getDescription().getVersion());
            sender.sendMessage(ChatColor.YELLOW + "Синхронизация: " + (syncEnabled ? ChatColor.GREEN + "включена" : ChatColor.RED + "выключена"));
            sender.sendMessage(ChatColor.YELLOW + "Защита: " + (protectEnabled ? ChatColor.GREEN + "включена" : ChatColor.RED + "выключена"));
            sender.sendMessage(ChatColor.YELLOW + "Защищенных игроков: " + protectedPlayers.size());
            sender.sendMessage(ChatColor.GRAY + "Используйте /sunsync help для помощи");
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "help":
                sendHelp(sender);
                break;
            case "status":
                showStatus(sender);
                break;
            case "on":
                enableSync(sender);
                break;
            case "off":
                disableSync(sender);
                break;
            case "protect":
                handleProtectCommand(sender, args);
                break;
            case "stats":
                handleStatsCommand(sender, args);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "Неизвестная команда! Используйте /sunsync help");
        }
        
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== SunSync Команды ===");
        sender.sendMessage(ChatColor.YELLOW + "/sunsync" + ChatColor.WHITE + " - Показать краткий статус");
        sender.sendMessage(ChatColor.YELLOW + "/sunsync status" + ChatColor.WHITE + " - Подробный статус плагина");
        sender.sendMessage(ChatColor.YELLOW + "/sunsync on" + ChatColor.WHITE + " - Включить синхронизацию");
        sender.sendMessage(ChatColor.YELLOW + "/sunsync off" + ChatColor.WHITE + " - Выключить синхронизацию");
        sender.sendMessage(ChatColor.YELLOW + "/sunsync protect on" + ChatColor.WHITE + " - Включить защиту игроков");
        sender.sendMessage(ChatColor.YELLOW + "/sunsync protect off" + ChatColor.WHITE + " - Выключить защиту игроков");
        sender.sendMessage(ChatColor.YELLOW + "/sunsync protect add <игрок>" + ChatColor.WHITE + " - Добавить в защищенные");
        sender.sendMessage(ChatColor.YELLOW + "/sunsync protect delete <игрок>" + ChatColor.WHITE + " - Убрать из защищенных");
        sender.sendMessage(ChatColor.YELLOW + "/sunsync protect list" + ChatColor.WHITE + " - Список защищенных игроков");
        sender.sendMessage(ChatColor.YELLOW + "/sunsync stats <clear|on|off>" + ChatColor.WHITE + " - Управление отслеживанием смертей");
    }

    private void showStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== SunSync Статус ===");
        sender.sendMessage(ChatColor.YELLOW + "Версия: " + ChatColor.WHITE + getDescription().getVersion());
        
        // Статус синхронизации
        String syncStatus = syncEnabled ? ChatColor.GREEN + "ВКЛЮЧЕНА" : ChatColor.RED + "ВЫКЛЮЧЕНА";
        sender.sendMessage(ChatColor.YELLOW + "Синхронизация: " + syncStatus);
        
        // Статус защиты
        String protectStatus = protectEnabled ? ChatColor.GREEN + "ВКЛЮЧЕНА" : ChatColor.RED + "ВЫКЛЮЧЕНА";
        sender.sendMessage(ChatColor.YELLOW + "Защита игроков: " + protectStatus);
        String statsStatus = statsEnabled ? ChatColor.GREEN + "ВКЛЮЧЕНО" : ChatColor.RED + "ВЫКЛЮЧЕНО";
        sender.sendMessage(ChatColor.YELLOW + "Отслеживание смертей: " + statsStatus);
        
        // Игроки онлайн
        Player[] online = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        sender.sendMessage(ChatColor.YELLOW + "Игроков онлайн: " + ChatColor.WHITE + online.length);
        
        if (online.length > 0) {
            sender.sendMessage(ChatColor.GRAY + "Список игроков:");
            for (Player p : online) {
                boolean isProtected = isPlayerProtected(p);
                String status = isProtected ? ChatColor.BLUE + " [ЗАЩИЩЕН]" : ChatColor.GREEN + " [ОБЫЧНЫЙ]";
                sender.sendMessage(ChatColor.GRAY + "  - " + ChatColor.WHITE + p.getName() + status);
            }
        }
        
        // Защищенные игроки
        sender.sendMessage(ChatColor.YELLOW + "Защищенных игроков: " + ChatColor.WHITE + protectedPlayers.size());
        if (!protectedPlayers.isEmpty()) {
            int onlineProtected = 0;
            int offlineProtected = 0;
            for (UUID uuid : protectedPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    onlineProtected++;
                } else {
                    offlineProtected++;
                }
            }
            sender.sendMessage(ChatColor.GRAY + "  Онлайн: " + ChatColor.GREEN + onlineProtected + 
                             ChatColor.GRAY + " | Офлайн: " + ChatColor.RED + offlineProtected);
        }
        
        // Статус файлов
        sender.sendMessage(ChatColor.YELLOW + "Файлы:");
        String sharedStatus = sharedStateFile.exists() ? ChatColor.GREEN + "ЕСТЬ" : ChatColor.RED + "НЕТ";
        String settingsStatus = settingsFile.exists() ? ChatColor.GREEN + "ЕСТЬ" : ChatColor.RED + "НЕТ";
        sender.sendMessage(ChatColor.GRAY + "  shared_state.yml: " + sharedStatus);
        sender.sendMessage(ChatColor.GRAY + "  settings.yml: " + settingsStatus);
        String deathsStatus = deathsFile.exists() ? ChatColor.GREEN + "ЕСТЬ" : ChatColor.RED + "НЕТ";
        sender.sendMessage(ChatColor.GRAY + "  deaths.yml: " + deathsStatus);
        
        // Дополнительная информация
        if (sharedStateFile.exists()) {
            long lastModified = sharedStateFile.lastModified();
            long timeDiff = System.currentTimeMillis() - lastModified;
            String timeAgo = formatTime(timeDiff);
            sender.sendMessage(ChatColor.GRAY + "  Последнее сохранение: " + ChatColor.WHITE + timeAgo + " назад");
        }
        
        // Статус синхронизации в реальном времени
        if (syncEnabled) {
            int activeNonProtected = 0;
            for (Player p : online) {
                if (!isPlayerProtected(p)) {
                    activeNonProtected++;
                }
            }
            sender.sendMessage(ChatColor.YELLOW + "Активных для синхронизации: " + ChatColor.WHITE + activeNonProtected);
        }
    }

    private String formatTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        if (seconds < 60) {
            return seconds + " сек";
        } else if (seconds < 3600) {
            return (seconds / 60) + " мин";
        } else if (seconds < 86400) {
            return (seconds / 3600) + " ч";
        } else {
            return (seconds / 86400) + " дн";
        }
    }

    private void enableSync(CommandSender sender) {
        if (syncEnabled) {
            sender.sendMessage(ChatColor.YELLOW + "Синхронизация уже включена!");
            return;
        }
        
        // Загружаем сохраненное состояние всем игрокам
        if (sharedStateFile.exists()) {
            Player randomPlayer = getRandomOnlinePlayer();
            if (randomPlayer != null) {
                loadSharedState(randomPlayer);
                // Синхронизируем со всеми остальными
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p != randomPlayer) {
                        syncFromPlayer(randomPlayer);
                        break;
                    }
                }
            }
        }
        
        syncEnabled = true;
        saveSettings();
        new SyncTask().runTaskTimer(this, 0, 1);
        
        Bukkit.broadcastMessage(ChatColor.GREEN + "Синхронизация включена!");
        sender.sendMessage(ChatColor.GREEN + "Синхронизация включена! Состояние загружено из файла.");
    }

    private void disableSync(CommandSender sender) {
        if (!syncEnabled) {
            sender.sendMessage(ChatColor.YELLOW + "Синхронизация уже выключена!");
            return;
        }
        
        // Сохраняем текущее состояние случайного игрока
        Player randomPlayer = getRandomOnlinePlayer();
        if (randomPlayer != null) {
            saveSharedState(randomPlayer);
        }
        
        syncEnabled = false;
        saveSettings();
        
        Bukkit.broadcastMessage(ChatColor.RED + "Синхронизация выключена!");
        sender.sendMessage(ChatColor.RED + "Синхронизация выключена! Текущее состояние сохранено в файл.");
    }

    private void handleProtectCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Используйте: /sunsync protect <on|off|add|delete|list>");
            return;
        }
        
        switch (args[1].toLowerCase()) {
            case "on":
                enableProtect(sender);
                break;
            case "off":
                disableProtect(sender);
                break;
            case "add":
                addProtectedPlayer(sender, args);
                break;
            case "delete":
            case "remove":
                removeProtectedPlayer(sender, args);
                break;
            case "list":
                listProtectedPlayers(sender);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "Используйте: /sunsync protect <on|off|add|delete|list>");
        }
    }

    private void enableProtect(CommandSender sender) {
        if (protectEnabled) {
            sender.sendMessage(ChatColor.YELLOW + "Защита уже включена!");
            return;
        }
        
        // Умная логика: если есть защищенные игроки онлайн, сохраняем состояние как будто они "вышли"
        List<Player> currentProtectedOnline = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (protectedPlayers.contains(p.getUniqueId())) {
                currentProtectedOnline.add(p);
            }
        }
        
        // Если после включения защиты останутся только защищенные игроки - сохраняем состояние
        List<Player> nonProtectedRemaining = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!protectedPlayers.contains(p.getUniqueId())) {
                nonProtectedRemaining.add(p);
            }
        }
        
        // Если незащищенных не останется, сохраняем от случайного онлайн игрока
        if (nonProtectedRemaining.isEmpty() && !currentProtectedOnline.isEmpty()) {
            Player randomPlayer = currentProtectedOnline.get(new Random().nextInt(currentProtectedOnline.size()));
            saveSharedState(randomPlayer);
            sender.sendMessage(ChatColor.GREEN + "Состояние сохранено от игрока " + randomPlayer.getName() + " (перед включением защиты)");
        }
        
        protectEnabled = true;
        saveSettings();
        sender.sendMessage(ChatColor.GREEN + "Защита игроков включена!");
        
        // Уведомляем защищенных игроков что они теперь "вне синхронизации"
        for (Player p : currentProtectedOnline) {
            p.sendMessage(ChatColor.BLUE + "Защита включена! Вы исключены из синхронизации.");
        }
    }

    private void disableProtect(CommandSender sender) {
        if (!protectEnabled) {
            sender.sendMessage(ChatColor.YELLOW + "Защита уже выключена!");
            return;
        }
        
        // При выключении защиты - защищенные игроки "заходят" обратно
        // Загружаем им сохраненное состояние если есть
        if (sharedStateFile.exists()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (protectedPlayers.contains(p.getUniqueId())) {
                    loadSharedState(p);
                    p.sendMessage(ChatColor.GREEN + "Защита выключена! Состояние загружено из файла.");
                }
            }
            
            // Синхронизируем всех остальных с первым "вернувшимся"
            Player firstReturned = null;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (protectedPlayers.contains(p.getUniqueId())) {
                    firstReturned = p;
                    break;
                }
            }
            
            if (firstReturned != null) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p != firstReturned && !protectedPlayers.contains(p.getUniqueId())) {
                        // Синхронизируем обычных игроков с вернувшимся
                        syncFromPlayer(firstReturned);
                        break;
                    }
                }
            }
        }
        
        protectEnabled = false;
        saveSettings();
        sender.sendMessage(ChatColor.RED + "Защита игроков выключена! Все игроки теперь синхронизируются.");
    }

    private void addProtectedPlayer(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Используйте: /sunsync protect add <игрок>");
            return;
        }
        
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Игрок не найден!");
            return;
        }
        
        if (protectedPlayers.contains(target.getUniqueId())) {
            sender.sendMessage(ChatColor.YELLOW + "Игрок " + target.getName() + " уже защищен!");
            return;
        }
        
        // При добавлении в защищенные - игрок "выходит" из синхронизации
        // Если это последний незащищенный - сохраняем состояние
        List<Player> remainingNonProtected = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != target && !protectedPlayers.contains(p.getUniqueId())) {
                remainingNonProtected.add(p);
            }
        }
        
        if (remainingNonProtected.isEmpty()) {
            // Сохраняем состояние от добавляемого игрока (он последний незащищенный)
            saveSharedState(target);
            sender.sendMessage(ChatColor.GREEN + "Состояние сохранено от " + target.getName() + " (последний незащищенный)");
        }
        
        protectedPlayers.add(target.getUniqueId());
        saveSettings();
        sender.sendMessage(ChatColor.GREEN + "Игрок " + target.getName() + " добавлен в защищенные!");
        target.sendMessage(ChatColor.GREEN + "Вы добавлены в защищенные игроки! Синхронизация на вас больше не влияет.");
    }

    private void removeProtectedPlayer(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Используйте: /sunsync protect delete <игрок>");
            return;
        }
        
        Player target = Bukkit.getPlayer(args[2]);
        UUID targetUUID = null;
        
        if (target != null) {
            targetUUID = target.getUniqueId();
        } else {
            // Попробуем найти по имени в списке защищенных
            for (UUID uuid : protectedPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.getName().equalsIgnoreCase(args[2])) {
                    targetUUID = uuid;
                    target = p;
                    break;
                }
            }
        }
        
        if (targetUUID == null || !protectedPlayers.contains(targetUUID)) {
            sender.sendMessage(ChatColor.RED + "Игрок не найден в списке защищенных!");
            return;
        }
        
        // При удалении из защищенных - игрок "заходит" обратно
        // Загружаем ему сохраненное состояние
        if (target != null && sharedStateFile.exists()) {
            loadSharedState(target);
            target.sendMessage(ChatColor.GREEN + "Вы удалены из защищенных! Состояние загружено из файла.");
            
            // Синхронизируем других с ним
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != target && !isPlayerProtected(p)) {
                    syncFromPlayer(target);
                    break;
                }
            }
        }
        
        protectedPlayers.remove(targetUUID);
        saveSettings();
        String playerName = target != null ? target.getName() : args[2];
        sender.sendMessage(ChatColor.GREEN + "Игрок " + playerName + " удален из защищенных!");
        if (target != null) {
            target.sendMessage(ChatColor.YELLOW + "Теперь на вас снова влияет синхронизация.");
        }
    }

    private void listProtectedPlayers(CommandSender sender) {
        if (protectedPlayers.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Нет защищенных игроков.");
            return;
        }
        
        sender.sendMessage(ChatColor.GOLD + "=== Защищенные игроки ===");
        for (UUID uuid : protectedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : "Офлайн";
            String status = p != null ? ChatColor.GREEN + " (онлайн)" : ChatColor.GRAY + " (офлайн)";
            sender.sendMessage(ChatColor.YELLOW + "- " + name + status);
        }
    }

    private void handleStatsCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /sunsync stats <show|clear|on|off> [player]");
            return;
        }
        if (args[1].equalsIgnoreCase("on")) {
            statsEnabled = true;
            saveSettings();
            sender.sendMessage("§aОтслеживании статистики включено.");
            return;
        } else if (args[1].equalsIgnoreCase("off")) {
            statsEnabled = false;
            saveSettings();
            sender.sendMessage("§aОтслеживании статистики отключено.");
            return;
        } else if (args[1].equalsIgnoreCase("show")) {
            if (args.length >= 3) {
                // Показать статистику конкретного игрока
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                if (target.hasPlayedBefore() || target.isOnline()) {
                    UUID uuid = target.getUniqueId();
                    int deaths = deathCounts.getOrDefault(uuid, 0);
                    sender.sendMessage("§e=== Статистика игрока " + args[2] + " ===");
                    sender.sendMessage("§6Смертей: §c" + deaths);
                } else {
                    sender.sendMessage("§cИгрок " + args[2] + " не найден.");
                }
            } else {
                // Показать топ статистику
                if (deathCounts.isEmpty()) {
                    sender.sendMessage("§eСтатистика пуста.");
                    return;
                }
                
                sender.sendMessage("§e=== Топ смертей ===");
                deathCounts.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(entry -> {
                        OfflinePlayer player = Bukkit.getOfflinePlayer(entry.getKey());
                        String name = player.getName() != null ? player.getName() : "Неизвестный";
                        sender.sendMessage("§6" + name + ": §c" + entry.getValue() + " смертей");
                    });
            }
            return;
        } else if (args[1].equalsIgnoreCase("clear")) {
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /sunsync stats clear <all|player>");
                return;
            }
            if (args[2].equalsIgnoreCase("all")) {
                deathCounts.clear();
                saveDeathCounts();
                sender.sendMessage("§aВсе счетчики смертей очищены.");
            } else {
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                if (target.hasPlayedBefore() || target.isOnline()) {
                    UUID uuid = target.getUniqueId();
                    deathCounts.remove(uuid);
                    saveDeathCounts();
                    sender.sendMessage("§aСчетчик смертей очищен для " + args[2]);
                } else {
                    sender.sendMessage("§cИгрок " + args[2] + " не найден.");
                }
            }
            return;
        } else {
            sender.sendMessage("§cUsage: /sunsync stats <show|clear|on|off> [player]");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("sunsync")) return null;
        
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.addAll(Arrays.asList("on", "off", "protect", "stats", "help", "status"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("protect")) {
            completions.addAll(Arrays.asList("on", "off", "add", "delete", "list"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("protect")) {
            if (args[1].equalsIgnoreCase("add")) {
                // Показываем незащищенных игроков
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!protectedPlayers.contains(p.getUniqueId())) {
                        completions.add(p.getName());
                    }
                }
            } else if (args[1].equalsIgnoreCase("delete")) {
                // Показываем защищенных игроков
                for (UUID uuid : protectedPlayers) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        completions.add(p.getName());
                    }
                }
            }
        } else if (args[0].equalsIgnoreCase("stats")) {
            if (args.length == 2) {
                return Arrays.asList("show", "clear", "on", "off");
            } else if (args.length == 3) {
                if (args[1].equalsIgnoreCase("clear")) {
                    List<String> suggestions = new ArrayList<>();
                    suggestions.add("all");
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        suggestions.add(p.getName());
                    }
                    return suggestions;
                } else if (args[1].equalsIgnoreCase("show")) {
                    List<String> suggestions = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        suggestions.add(p.getName());
                    }
                    return suggestions;
                }
            }
        }
        
        // Фильтруем по вводу
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                .collect(Collectors.toList());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!syncEnabled) return;
        
        Player player = event.getPlayer();
        
        // Если игрок защищен и защита включена - игнорируем его как будто он не зашел
        if (isPlayerProtected(player)) {
            player.sendMessage(ChatColor.BLUE + "Вы защищены от синхронизации!");
            return;
        }
        
        Bukkit.getScheduler().runTaskLater(this, () -> {
            // Считаем только незащищенных игроков
            List<Player> nonProtectedOnline = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isPlayerProtected(p)) {
                    nonProtectedOnline.add(p);
                }
            }
            
            if (nonProtectedOnline.size() == 1) {
                // Первый незащищенный игрок заходит - загружаем состояние из файла
                if (sharedStateFile.exists()) {
                    loadSharedState(player);
                    player.sendMessage(ChatColor.GREEN + "Состояние загружено из файла");
                }
            } else {
                // Не первый незащищенный игрок - синхронизируемся с кем-то из незащищенных онлайн
                Player source = getRandomNonProtectedPlayer();
                if (source != null && source != player) {
                    syncFromPlayer(source);
                    player.sendMessage(ChatColor.YELLOW + "Синхронизация с " + source.getName());
                }
            }
        }, 40); // Увеличил задержку для полной загрузки
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!syncEnabled) return;
        
        // Очищаем занятые контейнеры этого игрока
        UUID playerUUID = event.getPlayer().getUniqueId();
        occupiedContainers.entrySet().removeIf(entry -> entry.getValue().equals(playerUUID));
        
        // Если выходящий игрок защищен - игнорируем его выход
        if (isPlayerProtected(event.getPlayer())) {
            return;
        }
        
        Bukkit.getScheduler().runTaskLater(this, () -> {
            // Считаем только незащищенных игроков
            List<Player> nonProtectedOnline = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isPlayerProtected(p)) {
                    nonProtectedOnline.add(p);
                }
            }
            
            if (nonProtectedOnline.size() == 0) {
                // Последний незащищенный игрок выходит - сохраняем его состояние
                saveSharedState(event.getPlayer());
                getLogger().info("Состояние сохранено в файл (последний незащищенный игрок)");
            }
        }, 5); // Небольшая задержка чтобы Bukkit.getOnlinePlayers() обновился
    }

    private void saveSharedState(Player player) {
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.set("health", player.getHealth());
            config.set("absorption", player.getAbsorptionAmount());
            config.set("food", player.getFoodLevel());
            config.set("saturation", player.getSaturation());
            config.set("exhaustion", player.getExhaustion());
            config.set("level", player.getLevel());
            config.set("exp", player.getExp());
            config.set("total_exp", player.getTotalExperience());
            config.set("air", player.getRemainingAir());
            config.set("max_air", player.getMaximumAir());
            config.set("spawn_world", player.getBedSpawnLocation() != null ? player.getBedSpawnLocation().getWorld().getName() : null);
            config.set("spawn_x", player.getBedSpawnLocation() != null ? player.getBedSpawnLocation().getX() : null);
            config.set("spawn_y", player.getBedSpawnLocation() != null ? player.getBedSpawnLocation().getY() : null);
            config.set("spawn_z", player.getBedSpawnLocation() != null ? player.getBedSpawnLocation().getZ() : null);
            
            // Сохраняем инвентарь, броню и extra содержимое
            config.set("inventory", inventoryToBase64(player.getInventory()));
            config.set("armor", armorToBase64(player));
            config.set("extra", extraToBase64(player));
            config.set("ender_chest", inventoryToBase64(player.getEnderChest()));
            
            // Сохраняем эффекты зелий
            List<Map<String, Object>> effects = new ArrayList<>();
            for (PotionEffect effect : player.getActivePotionEffects()) {
                Map<String, Object> map = effect.serialize();
                effects.add(map);
            }
            config.set("effects", effects);

            config.save(sharedStateFile);
            getLogger().info("Состояние игрока " + player.getName() + " сохранено в файл");
        } catch (Exception e) {
            getLogger().severe("Ошибка сохранения состояния: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String armorToBase64(Player player) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            
            ItemStack[] armor = player.getInventory().getArmorContents();
            dataOutput.writeInt(armor.length);
            for (ItemStack item : armor) {
                dataOutput.writeObject(item);
            }
            
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        }
    }

    private String extraToBase64(Player player) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            
            ItemStack[] extra = player.getInventory().getExtraContents();
            dataOutput.writeInt(extra.length);
            for (ItemStack item : extra) {
                dataOutput.writeObject(item);
            }
            
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        }
    }

    private void loadSharedState(Player player) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(sharedStateFile);
        player.setHealth(config.getDouble("health", 20));
        player.setAbsorptionAmount(config.getDouble("absorption", 0));
        player.setFoodLevel(config.getInt("food", 20));
        player.setSaturation((float) config.getDouble("saturation", 5));
        player.setExhaustion((float) config.getDouble("exhaustion", 0));
        player.setLevel(config.getInt("level", 0));
        player.setExp((float) config.getDouble("exp", 0));
        player.setTotalExperience(config.getInt("total_exp", 0));
        player.setRemainingAir(config.getInt("air", 300));
        player.setMaximumAir(config.getInt("max_air", 300));

        String worldName = config.getString("spawn_world");
        if (worldName != null) {
            Location spawn = new Location(Bukkit.getWorld(worldName), config.getDouble("spawn_x"), config.getDouble("spawn_y"), config.getDouble("spawn_z"));
            player.setBedSpawnLocation(spawn, true);
        } else {
            player.setBedSpawnLocation(null, true);
        }

        // Загружаем инвентарь
        String invString = config.getString("inventory");
        if (invString != null) {
            try {
                applyInventoryFromBase64(player.getInventory(), invString);
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        // Загружаем броню
        String armorString = config.getString("armor");
        if (armorString != null) {
            try {
                applyArmorFromBase64(player, armorString);
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        // Загружаем прочее
        String extraString = config.getString("extra");
        if (extraString != null) {
            try {
                applyExtraFromBase64(player, extraString);
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        // Загружаем эндер-сундук
        String enderString = config.getString("ender_chest");
        if (enderString != null) {
            try {
                applyInventoryFromBase64(player.getEnderChest(), enderString);
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        // Загружаем эффекты
        List<Map<?, ?>> effectsList = config.getMapList("effects");
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (PotionEffect effect : p.getActivePotionEffects()) {
                p.removePotionEffect(effect.getType());
            }
        }
        for (Map<?, ?> map : effectsList) {
            PotionEffect effect = new PotionEffect((Map<String, Object>) map);
            player.addPotionEffect(effect);
        }
    }

    private String inventoryToBase64(Inventory inventory) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            
            // Сохраняем основной инвентарь
            ItemStack[] contents = inventory.getContents();
            dataOutput.writeInt(contents.length);
            for (ItemStack item : contents) {
                dataOutput.writeObject(item);
            }
            
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        }
    }

    private void applyInventoryFromBase64(Inventory inventory, String data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            
            int length = dataInput.readInt();
            ItemStack[] contents = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                contents[i] = (ItemStack) dataInput.readObject();
            }
            
            inventory.setContents(contents);
        }
    }

    private void applyArmorFromBase64(Player player, String data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            
            int length = dataInput.readInt();
            ItemStack[] armor = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                armor[i] = (ItemStack) dataInput.readObject();
            }
            
            player.getInventory().setArmorContents(armor);
        }
    }

    private void applyExtraFromBase64(Player player, String data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            
            int length = dataInput.readInt();
            ItemStack[] extra = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                extra[i] = (ItemStack) dataInput.readObject();
            }
            
            player.getInventory().setExtraContents(extra);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (syncing || !syncEnabled) return;
        Player player = event.getPlayer();
        if (isPlayerProtected(player)) return;
        
        // Проверяем блокировку контейнеров при клике по блоку
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Block clickedBlock = event.getClickedBlock();
            Material blockType = clickedBlock.getType();
            
            if (isContainerBlock(blockType)) {
                String containerKey = getLocationKey(clickedBlock.getLocation());
                UUID occupiedBy = occupiedContainers.get(containerKey);
                
                if (occupiedBy != null && !occupiedBy.equals(player.getUniqueId())) {
                    // Контейнер занят другим игроком
                    Player occupier = Bukkit.getPlayer(occupiedBy);
                    String occupierName = occupier != null ? occupier.getName() : "другим игроком";
                    
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "Контейнер уже используется игроком " + occupierName + "!");
                    return;
                }
                
                // Помечаем контейнер как занятый
                occupiedContainers.put(containerKey, player.getUniqueId());
                return;
            }
        }
        
        // Синхронизируем инвентарь при использовании предметов
        if ((event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) && event.hasItem()) {
            Bukkit.getScheduler().runTaskLater(this, () -> syncFromPlayer(player), 1);
        }
    }

    @EventHandler
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        if (syncing || !syncEnabled) return;
        Player player = event.getPlayer();
        if (isPlayerProtected(player)) return;
        // Синхронизируем точку спавна когда игрок ложится в кровать
        Bukkit.getScheduler().runTaskLater(this, () -> {
            syncSpawnLocation(player);
        }, 1);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (syncing) return;
        Block block = event.getBlock();
        if (block.getType() == Material.RESPAWN_ANCHOR || block.getType().toString().contains("BED")) {
            // Проверяем, является ли этот блок точкой спавна кого-то
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location spawnLoc = p.getBedSpawnLocation();
                if (spawnLoc != null && spawnLoc.getBlock().equals(block)) {
                    // Сбрасываем точку спавна у всех и показываем сообщение
                    for (Player pl : Bukkit.getOnlinePlayers()) {
                        pl.setBedSpawnLocation(null, true);
                        pl.sendMessage(ChatColor.WHITE + "Точка возрождения сброшена");
                    }
                    break;
                }
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Засчитываем смерть для того, кто умер первым (подвел команду)
        if (!deathSyncing && statsEnabled) {
            UUID uuid = event.getEntity().getUniqueId();
            deathCounts.put(uuid, deathCounts.getOrDefault(uuid, 0) + 1);
            saveDeathCounts(); // Сохраняем статистику сразу
        }
        
        if (deathSyncing) {
            // Если это не первая смерть, убираем дроп вещей и полностью убираем сообщение
            event.getDrops().clear();
            event.setDroppedExp(0);
            event.setDeathMessage(null);
            return;
        }
        
        deathSyncing = true;
        firstDeadPlayer = event.getEntity();
        lastDeathMessage = event.getDeathMessage();
        
        // Сохраняем оригинальное сообщение и показываем его сразу
        if (lastDeathMessage != null && !lastDeathMessage.isEmpty()) {
            Bukkit.broadcastMessage(lastDeathMessage);
        }
        // Убираем автоматическое сообщение для первого игрока тоже
        event.setDeathMessage(null);
        
        // Убиваем всех остальных игроков с такой же причиной
        Bukkit.getScheduler().runTaskLater(this, () -> {
            EntityDamageEvent.DamageCause cause = firstDeadPlayer.getLastDamageCause() != null ? 
                firstDeadPlayer.getLastDamageCause().getCause() : EntityDamageEvent.DamageCause.CUSTOM;
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player == firstDeadPlayer || player.isDead()) continue;
                
                // Создаем урон с той же причиной для одинаковой анимации
                EntityDamageEvent damageEvent = new EntityDamageEvent(player, cause, 1000);
                Bukkit.getPluginManager().callEvent(damageEvent);
                if (!damageEvent.isCancelled()) {
                    player.setHealth(0);
                }
            }
            
            // Сбрасываем флаги после завершения всех смертей
            Bukkit.getScheduler().runTaskLater(this, () -> {
                deathSyncing = false;
                firstDeadPlayer = null;
                lastDeathMessage = null;
            }, 2);
        }, 1);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (deathSyncing) return;
        
        Player respawnedPlayer = event.getPlayer();
        
        // Возрождаем всех остальных мертвых игроков
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player == respawnedPlayer) continue;
                if (player.isDead()) {
                    // Принудительно возрождаем игрока
                    player.spigot().respawn();
                }
            }
            
            // Синхронизируем состояние после возрождения
            Bukkit.getScheduler().runTaskLater(this, () -> {
                syncFromPlayer(respawnedPlayer);
            }, 5);
        }, 1);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (syncing || !syncEnabled) return;
        Player player = (Player) event.getWhoClicked();
        if (isPlayerProtected(player)) return;
        syncFromPlayer(player);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (syncing || !syncEnabled) return;
        Player player = (Player) event.getWhoClicked();
        if (isPlayerProtected(player)) return;
        syncFromPlayer(player);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!syncEnabled) return;
        Player player = (Player) event.getPlayer();
        
        // Освобождаем контейнер при закрытии
        if (event.getInventory().getLocation() != null) {
            InventoryType invType = event.getInventory().getType();
            
            // Проверяем что это не личный инвентарь и он действительно блокируется
            if (!PERSONAL_INVENTORY_TYPES.contains(invType) && isContainer(invType)) {
                String containerKey = getLocationKey(event.getInventory().getLocation());
                if (occupiedContainers.get(containerKey) != null && occupiedContainers.get(containerKey).equals(player.getUniqueId())) {
                    occupiedContainers.remove(containerKey);
                }
            }
        }
        
        if (syncing || isPlayerProtected(player)) return;
        syncFromPlayer(player);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!syncEnabled) return;
        Player player = (Player) event.getPlayer();
        
        InventoryType invType = event.getInventory().getType();
        if (PERSONAL_INVENTORY_TYPES.contains(invType)) {
            return;
        }
        
        // Проверяем блокировку контейнеров только для блочных инвентарей
        if (event.getInventory().getLocation() != null && isContainer(invType)) {
            String containerKey = getLocationKey(event.getInventory().getLocation());
            UUID occupiedBy = occupiedContainers.get(containerKey);
            
            if (occupiedBy != null && !occupiedBy.equals(player.getUniqueId())) {
                // Контейнер занят другим игроком
                Player occupier = Bukkit.getPlayer(occupiedBy);
                String occupierName = occupier != null ? occupier.getName() : "другим игроком";
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Контейнер уже используется игроком " + occupierName + "!");
                return;
            }
            
            // Помечаем контейнер как занятый (если еще не помечен)
            if (occupiedBy == null) {
                occupiedContainers.put(containerKey, player.getUniqueId());
            }
        }
        
        if (syncing || isPlayerProtected(player)) return;
        
        // Синхронизируем с задержкой для корректной работы
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            syncFromPlayer(player);
        }, 1);
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (syncing || !syncEnabled) return;
        Player player = event.getPlayer();
        if (isPlayerProtected(player)) return;
        syncFromPlayer(player);
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (syncing || !syncEnabled || !(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (isPlayerProtected(player)) return;
        syncFromPlayer(player);
    }

    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        if (syncing || !syncEnabled) return;
        Player player = event.getPlayer();
        if (isPlayerProtected(player)) return;
        syncFromPlayer(player);
    }

    @EventHandler
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (syncing || !syncEnabled) return;
        Player player = event.getPlayer();
        if (isPlayerProtected(player)) return;
        syncFromPlayer(player);
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (syncing || !syncEnabled || !(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (isPlayerProtected(player)) return;
        syncFromPlayer(player);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (syncing || deathSyncing || !syncEnabled || !(event.getEntity() instanceof Player)) return;
        Player damagedPlayer = (Player) event.getEntity();
        if (isPlayerProtected(damagedPlayer)) return;
        
        // Не синхронизируем урон если игрок умирает, это обработает onPlayerDeath
        if (damagedPlayer.getHealth() - event.getFinalDamage() > 0) {
            // Наносим такой же урон всем остальным игрокам для одинаковой анимации
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player == damagedPlayer || player.isDead() || isPlayerProtected(player)) continue;
                    
                    // Наносим реальный урон с причиной для анимации
                    player.damage(event.getFinalDamage(), event.getDamageSource());
                }
                syncFromPlayer(damagedPlayer);
            }, 1);
        }
    }

    private void syncSpawnLocation(Player sourcePlayer) {
        Location spawnLocation = sourcePlayer.getBedSpawnLocation();
        if (spawnLocation == null) {
            spawnLocation = sourcePlayer.getWorld().getSpawnLocation();
        }

        boolean anyChanged = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == sourcePlayer || player.isDead()) continue;
            Location oldSpawn = player.getBedSpawnLocation();
            player.setBedSpawnLocation(spawnLocation, true);
            if (!Objects.equals(oldSpawn, spawnLocation)) {
                anyChanged = true;
            }
        }
        
        // Показываем сообщение всем только если действительно что-то изменилось
        if (anyChanged) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(ChatColor.WHITE + "Точка возрождения установлена");
            }
        }
    }

    private void syncFromPlayer(Player sourcePlayer) {
        if (syncing || sourcePlayer.isDead() || !syncEnabled || isPlayerProtected(sourcePlayer)) return;
        syncing = true;

        try {
            ItemStack[] contents = sourcePlayer.getInventory().getContents();
            ItemStack[] armor = sourcePlayer.getInventory().getArmorContents();
            ItemStack[] extra = sourcePlayer.getInventory().getExtraContents();
            ItemStack[] enderContents = sourcePlayer.getEnderChest().getContents();

            double health = sourcePlayer.getHealth();
            double absorptionAmount = sourcePlayer.getAbsorptionAmount();
            int food = sourcePlayer.getFoodLevel();
            float saturation = sourcePlayer.getSaturation();
            float exhaustion = sourcePlayer.getExhaustion();
            
            // Синхронизируем опыт
            int expLevel = sourcePlayer.getLevel();
            float exp = sourcePlayer.getExp();
            int totalExp = sourcePlayer.getTotalExperience();
            
            // Синхронизируем воздух под водой, только если он уменьшается
            int remainingAir = sourcePlayer.getRemainingAir();
            int maximumAir = sourcePlayer.getMaximumAir();
            
            // Копируем эффекты зелий
            Collection<PotionEffect> effects = sourcePlayer.getActivePotionEffects();

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player == sourcePlayer || player.isDead() || isPlayerProtected(player)) continue;
                
                player.getInventory().setContents(contents.clone());
                player.getInventory().setArmorContents(armor.clone());
                player.getInventory().setExtraContents(extra.clone());
                player.getEnderChest().setContents(enderContents.clone());
                player.setHealth(Math.min(health, player.getMaxHealth()));
                player.setAbsorptionAmount(absorptionAmount);
                player.setFoodLevel(food);
                player.setSaturation(saturation);
                player.setExhaustion(exhaustion);
                
                // Синхронизируем опыт
                player.setLevel(expLevel);
                player.setExp(exp);
                player.setTotalExperience(totalExp);
                
                // Синхронизируем воздух под водой, только если он уменьшается
                if (remainingAir < player.getRemainingAir()) {
                    player.setRemainingAir(remainingAir);
                }
                player.setMaximumAir(maximumAir);
                
                // Синхронизируем эффекты зелий
                // Сначала очищаем все текущие эффекты
                for (PotionEffect effect : player.getActivePotionEffects()) {
                    player.removePotionEffect(effect.getType());
                }
                // Затем добавляем эффекты от источника
                for (PotionEffect effect : effects) {
                    player.addPotionEffect(effect);
                }
                
                // Синхронизируем точку спавна
                Location spawnLocation = sourcePlayer.getBedSpawnLocation();
                if (spawnLocation == null) {
                    spawnLocation = sourcePlayer.getWorld().getSpawnLocation();
                }
                Location oldSpawn = player.getBedSpawnLocation();
                player.setBedSpawnLocation(spawnLocation, true);
                // Убираем сообщение отсюда - оно показывается в syncSpawnLocation
                
                // Обновляем кэш для игрока
                updatePlayerCache(player);
            }
            
            // Обновляем кэш для источника
            updatePlayerCache(sourcePlayer);
            
        } finally {
            syncing = false;
        }
    }

    private void updatePlayerCache(Player player) {
        lastInventoryHash.put(player.getUniqueId(), getInventoryHash(player));
        lastHealth.put(player.getUniqueId(), player.getHealth());
        lastFood.put(player.getUniqueId(), player.getFoodLevel());
        lastAir.put(player.getUniqueId(), player.getRemainingAir());
        lastSpawnLocation.put(player.getUniqueId(), player.getBedSpawnLocation());
    }

    private int getInventoryHash(Player player) {
        return Arrays.hashCode(player.getInventory().getContents()) +
               Arrays.hashCode(player.getInventory().getArmorContents()) +
               Arrays.hashCode(player.getInventory().getExtraContents()) +
               Arrays.hashCode(player.getEnderChest().getContents());
    }

    private boolean hasPlayerChanged(Player player) {
        if (player.isDead()) return false;
        
        UUID uuid = player.getUniqueId();
        int currentInventoryHash = getInventoryHash(player);
        double currentHealth = player.getHealth();
        int currentFood = player.getFoodLevel();
        int currentAir = player.getRemainingAir();
        Location currentSpawn = player.getBedSpawnLocation();

        boolean changed = !lastInventoryHash.getOrDefault(uuid, 0).equals(currentInventoryHash) ||
                         !lastHealth.getOrDefault(uuid, 0.0).equals(currentHealth) ||
                         !lastFood.getOrDefault(uuid, 0).equals(currentFood) ||
                         (currentAir < lastAir.getOrDefault(uuid, 300)) ||
                         !Objects.equals(lastSpawnLocation.getOrDefault(uuid, null), currentSpawn);

        return changed;
    }

    private class SyncTask extends BukkitRunnable {
        @Override
        public void run() {
            if (syncing || deathSyncing || !syncEnabled) return;
            
            Player[] online = Bukkit.getOnlinePlayers().toArray(new Player[0]);
            if (online.length < 2) return;

            // Ищем живого незащищенного игрока с изменениями
            Player changedPlayer = null;
            for (Player player : online) {
                if (!player.isDead() && !isPlayerProtected(player) && hasPlayerChanged(player)) {
                    changedPlayer = player;
                    break;
                }
            }

            // Если нашли игрока с изменениями, синхронизируем от него
            if (changedPlayer != null) {
                syncFromPlayer(changedPlayer);
            }
        }
    }

    private Player getRandomOnlinePlayer() {
        Player[] online = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        if (online.length == 0) return null;
        return online[new Random().nextInt(online.length)];
    }

    private Player getRandomNonProtectedPlayer() {
        List<Player> nonProtected = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!protectedPlayers.contains(p.getUniqueId())) {
                nonProtected.add(p);
            }
        }
        if (nonProtected.isEmpty()) return null;
        return nonProtected.get(new Random().nextInt(nonProtected.size()));
    }

    private boolean isPlayerProtected(Player player) {
        return protectEnabled && protectedPlayers.contains(player.getUniqueId());
    }

    private String getLocationKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    // Список личных блоков, которые НЕ должны блокироваться
    private static final Set<InventoryType> PERSONAL_INVENTORY_TYPES = Set.of(
        InventoryType.ENDER_CHEST,    // Личный сундук каждого игрока
        InventoryType.ENCHANTING,     // Стол зачарований
        InventoryType.ANVIL,          // Наковальня
        InventoryType.GRINDSTONE,     // Точильный камень
        InventoryType.STONECUTTER,    // Резчик камня
        InventoryType.CARTOGRAPHY,    // Картографический стол
        InventoryType.LOOM,           // Ткацкий станок
        InventoryType.SMITHING,       // Кузнечный стол
        InventoryType.COMPOSTER,       // Компостер
        InventoryType.BEACON
    );

    private static final Set<Material> PERSONAL_BLOCK_TYPES = Set.of(
        Material.ENDER_CHEST,         // Личный сундук каждого игрока
        Material.ENCHANTING_TABLE,    // Стол зачарований
        Material.ANVIL,               // Наковальня
        Material.CHIPPED_ANVIL,       // Поврежденная наковальня
        Material.DAMAGED_ANVIL,       // Сломанная наковальня
        Material.GRINDSTONE,          // Точильный камень
        Material.STONECUTTER,         // Резчик камня
        Material.CARTOGRAPHY_TABLE,   // Картографический стол
        Material.LOOM,                // Ткацкий станок
        Material.SMITHING_TABLE,      // Кузнечный стол
        Material.COMPOSTER,            // Компостер
        Material.BEACON
    );

    private boolean isContainer(InventoryType type) {
        // Если это личный блок - не блокируем
        if (PERSONAL_INVENTORY_TYPES.contains(type)) {
            return false;
        }
        
        switch (type) {
            case CHEST:
            case CRAFTER:
            case SHULKER_BOX:
            case BARREL:
            case FURNACE:
            case BLAST_FURNACE:
            case SMOKER:
            case HOPPER:
            case DROPPER:
            case DISPENSER:
            case BREWING:
                return true;
            default:
                return false;
        }
    }

    private boolean isContainerBlock(Material material) {
        // Если это личный блок - не блокируем
        if (PERSONAL_BLOCK_TYPES.contains(material)) {
            return false;
        }
        
        switch (material) {
            case CRAFTER:
            case CHEST:
            case TRAPPED_CHEST:
            case SHULKER_BOX:
            case WHITE_SHULKER_BOX:
            case ORANGE_SHULKER_BOX:
            case MAGENTA_SHULKER_BOX:
            case LIGHT_BLUE_SHULKER_BOX:
            case YELLOW_SHULKER_BOX:
            case LIME_SHULKER_BOX:
            case PINK_SHULKER_BOX:
            case GRAY_SHULKER_BOX:
            case LIGHT_GRAY_SHULKER_BOX:
            case CYAN_SHULKER_BOX:
            case PURPLE_SHULKER_BOX:
            case BLUE_SHULKER_BOX:
            case BROWN_SHULKER_BOX:
            case GREEN_SHULKER_BOX:
            case RED_SHULKER_BOX:
            case BLACK_SHULKER_BOX:
            case BARREL:
            case FURNACE:
            case BLAST_FURNACE:
            case SMOKER:
            case HOPPER:
            case DROPPER:
            case DISPENSER:
            case BREWING_STAND:
                return true;
            default:
                return false;
        }
    }

    private class DeathPlaceholder extends PlaceholderExpansion {
        @Override
        public @NotNull String getIdentifier() {
            return "sunsync";
        }

        @Override
        public @NotNull String getAuthor() {
            return Main.this.getDescription().getAuthors().toString();
        }

        @Override
        public @NotNull String getVersion() {
            return Main.this.getDescription().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public @Nullable String onPlaceholderRequest(Player player, @NotNull String identifier) {
            if (player == null) return null;
            if (identifier.equals("deaths")) {
                return String.valueOf(deathCounts.getOrDefault(player.getUniqueId(), 0));
            }
            return null;
        }
    }
} 