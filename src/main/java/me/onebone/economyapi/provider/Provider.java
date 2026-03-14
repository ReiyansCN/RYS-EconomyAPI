package me.onebone.economyapi.provider;

import java.io.File;
import java.util.LinkedHashMap;

public interface Provider {

    // checked 方法的返回状态码
    int RET_SUCCESS = 1;
    int RET_INVALID = 0;
    int RET_NO_ACCOUNT = -3;

    void init(File path);

    void open();

    void save();

    void close();

    boolean accountExists(String currencyName, String id);

    boolean accountExists(String id);

    boolean removeAccount(String currencyName, String id);

    boolean removeAccount(String id);

    boolean createAccount(String currencyName, String id, double defaultMoney);

    boolean createAccount(String id, double defaultMoney);

    boolean setMoney(String currencyName, String id, double amount);

    boolean setMoney(String id, double amount);

    boolean addMoney(String currencyName, String id, double amount);

    boolean addMoney(String id, double amount);

    boolean reduceMoney(String currencyName, String id, double amount);

    boolean reduceMoney(String id, double amount);

    double getMoney(String currencyName, String id);

    double getMoney(String id);

    LinkedHashMap<String, Double> getAll(String currencyName);

    LinkedHashMap<String, Double> getAll();

    String getName();

    /**
     * 设置余额并校验上限。
     * <p>默认实现不保证原子性，Provider 实现应覆写此方法并通过加锁等方式确保
     * 检查账户 + 校验上限 + 写入在同一临界区内完成。</p>
     */
    default int setMoneyChecked(String currencyName, String id, double amount, double maxMoney) {
        if (!accountExists(currencyName, id)) return RET_NO_ACCOUNT;
        if (amount > maxMoney) return RET_INVALID;
        setMoney(currencyName, id, amount);
        return RET_SUCCESS;
    }

    /**
     * 增加余额并校验上限。
     * <p>默认实现不保证原子性，Provider 实现应覆写此方法并通过加锁等方式确保
     * 读取余额 + 校验上限 + 写入在同一临界区内完成。</p>
     */
    default int addMoneyChecked(String currencyName, String id, double amount, double maxMoney) {
        double money = getMoney(currencyName, id);
        if (money == -1) return RET_NO_ACCOUNT;
        if (money + amount > maxMoney) return RET_INVALID;
        addMoney(currencyName, id, amount);
        return RET_SUCCESS;
    }

    /**
     * 扣减余额并校验下限（不允许为负）。
     * <p>默认实现不保证原子性，Provider 实现应覆写此方法并通过加锁等方式确保
     * 读取余额 + 校验下限 + 写入在同一临界区内完成。</p>
     */
    default int reduceMoneyChecked(String currencyName, String id, double amount) {
        double money = getMoney(currencyName, id);
        if (money == -1) return RET_NO_ACCOUNT;
        if (money - amount < 0) return RET_INVALID;
        reduceMoney(currencyName, id, amount);
        return RET_SUCCESS;
    }
}
