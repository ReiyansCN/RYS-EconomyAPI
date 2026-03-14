package me.onebone.economyapi.command;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.ConsoleCommandSender;
import cn.nukkit.command.PluginCommand;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.lang.LangCode;
import cn.nukkit.utils.Config;
import me.onebone.economyapi.EconomyAPI;
import me.onebone.economyapi.provider.Provider;
import me.onebone.economyapi.provider.YamlProvider;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

import static me.onebone.economyapi.EconomyAPI.MAIN_CONFIG;
import static me.onebone.economyapi.EconomyAPI.serverLangCode;

public class MigrateDataCommand extends PluginCommand<EconomyAPI> {
    private final EconomyAPI plugin;

    public MigrateDataCommand(EconomyAPI plugin) {
        super("migratedata", plugin);

        this.setDescription("Migrate data from YAML to database");
        this.setUsage("/migratedata confirm");
        this.plugin = plugin;

        commandParameters.clear();
        commandParameters.put("default", new CommandParameter[]{
                CommandParameter.newType("confirm", false, cn.nukkit.command.data.CommandParamType.TEXT)
        });
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!this.plugin.isEnabled()) return false;

        // 仅控制台可用
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(EconomyAPI.getI18n().tr(serverLangCode, "migratedata-console-only"));
            return false;
        }
        LangCode langCode = serverLangCode;

        // 检查当前 provider 是否为 YAML（YAML 迁移到自身无意义）
        if (plugin.getProvider() instanceof YamlProvider) {
            sender.sendMessage(EconomyAPI.getI18n().tr(langCode, "migratedata-not-database"));
            return false;
        }

        // 需要 confirm 参数防止误操作
        if (args.length < 1 || !args[0].equalsIgnoreCase("confirm")) {
            sender.sendMessage(EconomyAPI.getI18n().tr(langCode, "migratedata-need-confirm"));
            return false;
        }

        sender.getServer().getScheduler().scheduleTask(plugin, () -> {
            try {
                Provider provider = plugin.getProvider();
                String providerName = provider.getName();
                sender.sendMessage(EconomyAPI.getI18n().tr(langCode, "migratedata-start", providerName));

                File dataFolder = plugin.getDataFolder();
                int totalMigrated = 0;
                int totalSkipped = 0;
                int totalFailed = 0;
                boolean hasData = false;

                for (String currencyName : MAIN_CONFIG.getCurrencyList()) {
                    File yamlFile = new File(dataFolder, "money" + File.separator + currencyName + ".yml");
                    if (!yamlFile.exists()) continue;

                    Config config = new Config(yamlFile, Config.YAML);
                    Object moneySection = config.getRootSection().get("money");
                    if (!(moneySection instanceof LinkedHashMap)) continue;

                    hasData = true;
                    LinkedHashMap<String, Object> moneyData = (LinkedHashMap<String, Object>) moneySection;
                    int migrated = 0;
                    int skipped = 0;
                    int failed = 0;
                    LinkedHashMap<String, MigrationEntry> normalizedAccounts = new LinkedHashMap<>();

                    for (var entry : moneyData.entrySet()) {
                        String rawKey = entry.getKey();

                        // 解析金额，单条解析失败不中断整批迁移
                        double amount;
                        try {
                            Object value = entry.getValue();
                            if (value instanceof Integer) {
                                amount = ((Integer) value).doubleValue();
                            } else if (value instanceof Double) {
                                amount = (Double) value;
                            } else if (value instanceof String) {
                                amount = Double.parseDouble((String) value);
                            } else {
                                failed++;
                                plugin.getLogger().warning("Skipped invalid value for " + rawKey + " in " + currencyName);
                                continue;
                            }
                        } catch (NumberFormatException e) {
                            failed++;
                            plugin.getLogger().warning("Skipped unparseable value for " + rawKey + " in " + currencyName + ": " + entry.getValue());
                            continue;
                        }

                        MigrationEntry migrationEntry = normalizePlayerKey(rawKey, amount);
                        MigrationEntry existingEntry = normalizedAccounts.get(migrationEntry.normalizedKey());
                        if (existingEntry == null) {
                            normalizedAccounts.put(migrationEntry.normalizedKey(), migrationEntry);
                            continue;
                        }

                        if (migrationEntry.isUuidSource() && !existingEntry.isUuidSource()) {
                            normalizedAccounts.put(migrationEntry.normalizedKey(), migrationEntry);
                            skipped++;
                            plugin.getLogger().warning("Replaced legacy source entry for " + rawKey
                                    + " with UUID entry for " + migrationEntry.normalizedKey() + " in " + currencyName);
                            continue;
                        }

                        skipped++;
                        plugin.getLogger().warning("Skipped duplicate source entry for " + rawKey
                                + " resolved to " + migrationEntry.normalizedKey() + " in " + currencyName);
                    }

                    for (MigrationEntry migrationEntry : normalizedAccounts.values()) {
                        // 目标 provider 中已存在则跳过
                        if (provider.accountExists(currencyName, migrationEntry.normalizedKey())) {
                            skipped++;
                            continue;
                        }

                        // 写入并校验结果
                        if (provider.createAccount(currencyName, migrationEntry.normalizedKey(), migrationEntry.amount())) {
                            migrated++;
                        } else {
                            failed++;
                            plugin.getLogger().warning("Failed to create account for " + migrationEntry.normalizedKey()
                                    + " in " + currencyName);
                        }
                    }

                    totalMigrated += migrated;
                    totalSkipped += skipped;
                    totalFailed += failed;
                    sender.sendMessage(EconomyAPI.getI18n().tr(langCode, "migratedata-progress",
                            currencyName, String.valueOf(migrated), String.valueOf(skipped), String.valueOf(failed)));
                }

                if (!hasData) {
                    sender.sendMessage(EconomyAPI.getI18n().tr(langCode, "migratedata-no-data"));
                    return;
                }

                // 保存迁移后的数据
                provider.save();

                sender.sendMessage(EconomyAPI.getI18n().tr(langCode, "migratedata-complete",
                        String.valueOf(totalMigrated), String.valueOf(totalSkipped), String.valueOf(totalFailed)));
            } catch (Exception e) {
                sender.sendMessage(EconomyAPI.getI18n().tr(langCode, "migratedata-error", e.getMessage()));
                plugin.getLogger().error("Migration failed", e);
            }
        }, true);

        return true;
    }

    /**
     * 标识归一化：与 EconomyAPI.checkAndConvertLegacy 保持一致。
     * 若 key 已是 UUID 格式则直接小写返回；否则当作玩家名尝试解析 UUID，
     * 解析成功用小写 UUID，失败则用小写名称。
     */
    private MigrationEntry normalizePlayerKey(String rawKey, double amount) {
        // 先尝试作为 UUID 解析
        try {
            UUID uuid = UUID.fromString(rawKey);
            return new MigrationEntry(uuid.toString().toLowerCase(), amount, true);
        } catch (IllegalArgumentException ignored) {
        }

        // 用原始大小写查找 UUID（与 EconomyAPI.checkAndConvertLegacy(String) 一致）
        Optional<UUID> uuid = Server.getInstance().lookupName(rawKey);
        if (uuid.isEmpty()) {
            Player onlinePlayer = Server.getInstance().getPlayerExact(rawKey);
            if (onlinePlayer != null) {
                uuid = Optional.of(onlinePlayer.getUniqueId());
            }
        }
        // UUID 找到则用小写 UUID，否则用小写名称
        return new MigrationEntry(uuid.map(u -> u.toString().toLowerCase()).orElse(rawKey.toLowerCase()), amount, false);
    }

    private record MigrationEntry(String normalizedKey, double amount, boolean isUuidSource) {
    }
}
