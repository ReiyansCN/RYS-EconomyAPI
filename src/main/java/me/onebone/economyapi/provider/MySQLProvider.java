package me.onebone.economyapi.provider;

import cn.nukkit.Server;
import cn.nukkit.utils.ConfigSection;
import com.smallaswater.easysqlx.common.data.SqlData;
import com.smallaswater.easysqlx.common.data.SqlDataList;
import com.smallaswater.easysqlx.exceptions.MySqlLoginException;
import com.smallaswater.easysqlx.mysql.manager.SqlManager;
import com.smallaswater.easysqlx.mysql.utils.DataType;
import com.smallaswater.easysqlx.mysql.utils.TableType;
import com.smallaswater.easysqlx.mysql.utils.UserData;
import me.onebone.economyapi.EconomyAPI;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static me.onebone.economyapi.EconomyAPI.MAIN_CONFIG;

public class MySQLProvider implements Provider {
    private static SqlManager manager;
    private static String TABLE_NAME_PREFIX = "";
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public static void initTablePrefix(String prefix) {
        MySQLProvider.TABLE_NAME_PREFIX = prefix;
    }

    @Override
    public void init(File path) {
        if (MySQLProvider.manager != null && MySQLProvider.manager.isEnable()) {
            EconomyAPI.getInstance().getLogger().warning("MySQL is already initialized.");
            return;
        }

        if (!MAIN_CONFIG.getConfig().exists("sql.mysql")) {
            EconomyAPI.getInstance().getLogger().error("MySQL is not configured.");
            return;
        }
        ConfigSection mysqlSection = MAIN_CONFIG.getConfig().getSection("sql.mysql");
        String host = mysqlSection.getString("host", "localhost");
        int port = mysqlSection.getInt("port", 3306);
        String database = mysqlSection.getString("database", "economy");
        String username = mysqlSection.getString("username", "root");
        String password = mysqlSection.getString("password", "root123456");
        String tablePrefix = mysqlSection.getString("table-prefix", "v1_");
        MySQLProvider.initTablePrefix(tablePrefix);

        Server.getInstance().getScheduler().scheduleTask(EconomyAPI.getInstance(), () -> {
            try {
                SqlManager manager = new SqlManager(EconomyAPI.getInstance(), new UserData(
                        username, password, host, port, database
                ));
                MySQLProvider.manager = manager;

                MAIN_CONFIG.getCurrencyList().forEach(currencyName -> {
                    manager.createTable(
                            TABLE_NAME_PREFIX + currencyName,
                            new TableType("player", DataType.getUUID(), true),
                            new TableType("money", DataType.getBIGINT(), false)
                    );
                });
                EconomyAPI.getInstance().getLogger().info("MySQL initialized!");
            } catch (MySqlLoginException e) {
                EconomyAPI.getInstance().getLogger().error("MySQL connection failed.", e);
                Server.getInstance().getPluginManager().disablePlugin(EconomyAPI.getInstance());
            }
        }, true);
    }

    @Override
    public void open() {
        if (MySQLProvider.manager == null) return;
        if (!MySQLProvider.manager.isEnable()) {
            this.init(null);
        }
    }

    @Override
    public void save() {
        // not required in MySQL.
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (MySQLProvider.manager == null) return;
            MySQLProvider.manager.disable();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean accountExists(String currencyName, String id) {
        lock.readLock().lock();
        try {
            if (!MAIN_CONFIG.getCurrencyList().contains(currencyName)) return false;
            if (MySQLProvider.manager.isExistTable(TABLE_NAME_PREFIX + currencyName)) {
                return MySQLProvider.manager.isExistsData(TABLE_NAME_PREFIX + currencyName, "player", id);
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean accountExists(String id) {
        return accountExists(MAIN_CONFIG.getDefaultCurrency().getName(), id);
    }

    @Override
    public boolean removeAccount(String currencyName, String id) {
        lock.writeLock().lock();
        try {
            if (!MAIN_CONFIG.getCurrencyList().contains(currencyName)) return false;
            if (MySQLProvider.manager.isExistTable(TABLE_NAME_PREFIX + currencyName)) {
                return MySQLProvider.manager.deleteData(TABLE_NAME_PREFIX + currencyName, new SqlData("player", id));
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean removeAccount(String id) {
        return removeAccount(MAIN_CONFIG.getDefaultCurrency().getName(), id);
    }

    @Override
    public boolean createAccount(String currencyName, String id, double defaultMoney) {
        lock.writeLock().lock();
        try {
            if (!MAIN_CONFIG.getCurrencyList().contains(currencyName)) return false;
            long money = (long) (defaultMoney * 100);
            if (!accountExists(currencyName, id)) {
                SqlData sqlData = new SqlData("player", id).put("money", money);
                return MySQLProvider.manager.insertData(TABLE_NAME_PREFIX + currencyName, sqlData);
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean createAccount(String id, double defaultMoney) {
        return createAccount(MAIN_CONFIG.getDefaultCurrency().getName(), id, defaultMoney);
    }

    @Override
    public boolean setMoney(String currencyName, String id, double amount) {
        lock.writeLock().lock();
        try {
            if (!MAIN_CONFIG.getCurrencyList().contains(currencyName)) return false;
            long money = (long) (amount * 100);
            return MySQLProvider.manager.setData(TABLE_NAME_PREFIX + currencyName, new SqlData("money", money), new SqlData("player", id));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean setMoney(String id, double amount) {
        return setMoney(MAIN_CONFIG.getDefaultCurrency().getName(), id, amount);
    }

    @Override
    public boolean addMoney(String currencyName, String id, double amount) {
        lock.writeLock().lock();
        try {
            if (!MAIN_CONFIG.getCurrencyList().contains(currencyName)) return false;
            double current = getMoney(currencyName, id);
            if (current == -1) return false;
            long money = (long) ((current + amount) * 100);
            return MySQLProvider.manager.setData(TABLE_NAME_PREFIX + currencyName, new SqlData("money", money), new SqlData("player", id));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean addMoney(String id, double amount) {
        return addMoney(MAIN_CONFIG.getDefaultCurrency().getName(), id, amount);
    }

    @Override
    public boolean reduceMoney(String currencyName, String id, double amount) {
        lock.writeLock().lock();
        try {
            if (!MAIN_CONFIG.getCurrencyList().contains(currencyName)) return false;
            double current = getMoney(currencyName, id);
            if (current == -1) return false;
            long money = (long) ((current - amount) * 100);
            return MySQLProvider.manager.setData(TABLE_NAME_PREFIX + currencyName, new SqlData("money", money), new SqlData("player", id));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean reduceMoney(String id, double amount) {
        return reduceMoney(MAIN_CONFIG.getDefaultCurrency().getName(), id, amount);
    }

    @Override
    public double getMoney(String currencyName, String id) {
        lock.readLock().lock();
        try {
            if (!MAIN_CONFIG.getCurrencyList().contains(currencyName)) return -1;
            SqlDataList<SqlData> sqlDataList = MySQLProvider.manager.getData(TABLE_NAME_PREFIX + currencyName, "money", new SqlData("player", id));
            if (sqlDataList.isEmpty()) return -1;
            return sqlDataList.get(0).getLong("money") / 100.0;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public double getMoney(String id) {
        return getMoney(MAIN_CONFIG.getDefaultCurrency().getName(), id);
    }

    @Override
    public LinkedHashMap<String, Double> getAll(String currencyName) {
        lock.readLock().lock();
        try {
            LinkedHashMap<String, Double> map = new LinkedHashMap<>();
            if (!MAIN_CONFIG.getCurrencyList().contains(currencyName)) return map;
            SqlData emptyData = new SqlData();
            SqlDataList<SqlData> sqlDataList = MySQLProvider.manager.getData(TABLE_NAME_PREFIX + currencyName, "*", emptyData);
            if (sqlDataList == null) {
                return map;
            }
            for (SqlData sqlData : sqlDataList) {
                LinkedHashMap<String, Object> data = sqlData.getData();
                try {
                    String playerId = (String) data.get("player");
                    long moneyObj = (long) data.get("money");
                    map.put(playerId, moneyObj / 100.0);
                } catch (Exception e) {
                    EconomyAPI.getInstance().getLogger().error("Error processing SqlData: " + sqlData, e);
                }
            }
            return map;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public LinkedHashMap<String, Double> getAll() {
        return getAll(MAIN_CONFIG.getDefaultCurrency().getName());
    }

    @Override
    public String getName() {
        return "MySQL";
    }

    @Override
    public int setMoneyChecked(String currencyName, String id, double amount, double maxMoney) {
        lock.writeLock().lock();
        try {
            if (!MAIN_CONFIG.getCurrencyList().contains(currencyName)) return RET_NO_ACCOUNT;
            if (!accountExists(currencyName, id)) return RET_NO_ACCOUNT;
            if (amount > maxMoney) return RET_INVALID;
            long money = (long) (amount * 100);
            MySQLProvider.manager.setData(TABLE_NAME_PREFIX + currencyName, new SqlData("money", money), new SqlData("player", id));
            return RET_SUCCESS;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int addMoneyChecked(String currencyName, String id, double amount, double maxMoney) {
        lock.writeLock().lock();
        try {
            if (!MAIN_CONFIG.getCurrencyList().contains(currencyName)) return RET_NO_ACCOUNT;
            double current = getMoney(currencyName, id);
            if (current == -1) return RET_NO_ACCOUNT;
            if (current + amount > maxMoney) return RET_INVALID;
            long money = (long) ((current + amount) * 100);
            MySQLProvider.manager.setData(TABLE_NAME_PREFIX + currencyName, new SqlData("money", money), new SqlData("player", id));
            return RET_SUCCESS;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int reduceMoneyChecked(String currencyName, String id, double amount) {
        lock.writeLock().lock();
        try {
            if (!MAIN_CONFIG.getCurrencyList().contains(currencyName)) return RET_NO_ACCOUNT;
            double current = getMoney(currencyName, id);
            if (current == -1) return RET_NO_ACCOUNT;
            if (current - amount < 0) return RET_INVALID;
            long money = (long) ((current - amount) * 100);
            MySQLProvider.manager.setData(TABLE_NAME_PREFIX + currencyName, new SqlData("money", money), new SqlData("player", id));
            return RET_SUCCESS;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
