package net.enabify.chatJP;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Statistic;

import java.util.Arrays;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

import java.util.UUID;

public final class ChatJP extends JavaPlugin implements Listener {

    private File dataFile;
    private FileConfiguration dataConfig;
    private final Map<UUID, String> playerGroups = new HashMap<>();

    private static final long TICKS_PER_HOUR = 20L * 60L * 60L;
    private static final long SIX_HOURS_TICKS = 24L * TICKS_PER_HOUR; // 一時的に24時間に延長
    private static final long TWO_HOURS_TICKS = 2L * TICKS_PER_HOUR;

    // NGワードの設定
    String[] ngwords = {
            "<@!*&*[0-9]+>", //個人へのメンションをブロック
            "@here", //hereメンションをブロック
            "@everyone", //everyoneメンションをブロック
            "@", //@を含むメッセージをブロック
            "discord\\.gg", //Discordの招待リンクをブロック
            "discord\\.com/invite", //Discordの招待リンクをブロック
            "https?:\\/\\/[^\s]+", //URLをブロック
    };


    @Override
    public void onEnable() {
        // Plugin startup logic
        loadDataFile();
        loadGroups();

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ChatJP plugin enabled!");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        saveDataFile();

        getLogger().info("ChatJP plugin disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // プレイヤーのみが利用可能
        if (!(sender instanceof Player)) return false;

        UUID senderUUID = ((Player) sender).getUniqueId();

        if (command.getName().equalsIgnoreCase("group")) {
            if (args.length < 1) {
                playerGroups.remove(senderUUID);
                dataConfig.set(senderUUID.toString(), null);
                saveDataFile();

                sender.sendMessage(ChatColor.GOLD+"[グループチャット] "+ChatColor.WHITE+"グループから退出しました。");

                return true;
            }

            String groupId = args[0];
            
            // グループIDのバリデーション：ローマ字と数字のみ、「global」は除外
            if (!groupId.matches("^[a-zA-Z0-9]+$")) {
                sender.sendMessage(ChatColor.GOLD+"[グループチャット] "+ChatColor.WHITE+"グループIDはローマ字と数字のみで構成してください。");
                return true;
            }
            
            if (groupId.equalsIgnoreCase("global")) {
                sender.sendMessage(ChatColor.GOLD+"[グループチャット] "+ChatColor.WHITE+"グループID「global」は使用できません。");
                return true;
            }

            playerGroups.put(senderUUID, groupId);
            dataConfig.set(senderUUID.toString(), groupId);
            saveDataFile();

            sender.sendMessage(ChatColor.GOLD+"[グループチャット] "+ChatColor.WHITE+"グループ " + groupId + " に参加しました！");

            return true;
        }

        // ここからはw,tell,msgコマンドの処理
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "使い方: /tell プレイヤー名 メッセージ");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "プレイヤーが見つかりませんでした...");
            return true;
        }


        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        //日本語化
        String result = translate(message);

        String decoratedSenderName = buildChatDisplayName((Player) sender);
        String decoratedTargetName = buildChatDisplayName((Player) target);

        target.sendMessage(ChatColor.WHITE + "[" + decoratedSenderName + " -> " + decoratedTargetName + "] " + result);

        // コンソールに表示
        getLogger().info("[" + sender.getName() + " -> " + target.getName() + "] " + result);

        // 送信者にもログが残るようにする
        sender.sendMessage(ChatColor.WHITE + "[" + decoratedSenderName + " -> " + decoratedTargetName + "] " + result);

        return true;
    }

    /**
     * プレイヤーがチャット発言した時に呼び出されるメソッド
     * @param event
     */
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {


        // プレイヤーの発言ではない場合は、そのまま無視する
        if (!(event.getPlayer() instanceof org.bukkit.entity.Player)) {
            return;
        }

        String message = event.getMessage();

        // コマンド実行の場合は、そのまま無視する
        if ( message.startsWith("/") ) {
            return;
        }

        // いったん、グループチャット機能（グループに所属していた場合）
        UUID senderUUID = event.getPlayer().getUniqueId();
        String senderGroup = getPlayerGroup(senderUUID);

        // 「!」でグループではなく、全体でチャット
        if ( message.startsWith("!") ) {

            // メッセージの頭の「!」を削除
            message = message.substring(1); // 先頭の1文字を削除
            // メッセージを日本語化
            String result = translate(message);
            String buildBeginnerMark = buildBeginnerMark(event.getPlayer());
            event.setMessage(result + buildBeginnerMark);

            return;
        }


        if (senderGroup == null) {
            //グループには参加していなかった場合（通常チャット）

            // メッセージを日本語化
            String result = translate(message);
            String buildBeginnerMark = buildBeginnerMark(event.getPlayer());
            event.setMessage(result + buildBeginnerMark);

        } else {
            // グループに参加していた場合（グループチャット）
            event.setCancelled(true); // 通常のチャットはキャンセル

            // メッセージを日本語化
            String result = translate(message);

                    String msg = ChatColor.GOLD + "[グループ | " + senderGroup + "] " +
                        ChatColor.WHITE + "<" + buildChatDisplayName(event.getPlayer()) + "> " + result;

            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                String targetGroup = getPlayerGroup(onlinePlayer.getUniqueId());
                if (senderGroup.equals(targetGroup)) {
                    onlinePlayer.sendMessage(msg);
                }
            }

            // コンソールに表示
            getLogger().info(msg);
        }
    }


    /**
     * 指定されたメッセージ内のNGワードを伏字にする関数
     * @param message 元のメッセージ
     * @param ngWords 伏字にすべき単語の正規表現配列
     * @return 伏字にされたメッセージ
     */
    private String maskNGWord(String message, String[] ngWords) {
        if (ngWords == null || ngWords.length == 0) {
            return message;
        }

        for (String ngWord : ngWords) {
            if (ngWord != null && !ngWord.isEmpty()) {
                message = message.replaceAll("(?i)" + ngWord, "****");
            }
        }

        return message;
    }

    /**
     * 特定の数字コード（52、08、58、082）をランダムな挨拶に変換する
     * @param message 元のメッセージ
     * @return 変換されたメッセージ、または変換しない場合はnull
     */
    private String translateGreetingCode(String message) {
        Random random = new Random();
        
        if (message.equals("52")) {
            String[] greetings = {
                "こんにちは", "こんにちは！", "こんにちは^^", "こんにちはー", "こんにちは～",
                "こんです", "こんです！", "こんです^^", "こんですー", "こんです～",
            };
            return greetings[random.nextInt(greetings.length)];
        } else if (message.equals("08")) {
            String[] greetings = {
                "おはようございます", "おはようございます！", "おはようございます^^",
                "おはようございます～", "おはようございますー",
                "おはです", "おはです！", "おはです^^", "おはです～", "おはですー"
            };
            return greetings[random.nextInt(greetings.length)];
        } else if (message.equals("58")) {
            String[] greetings = {
                "こんばんは", "こんばんは！", "こんばんは^^", "こんばんは～", "こんばんはー",
                "こんです", "こんです！", "こんです^^", "こんですー", "こんです～"
            };
            return greetings[random.nextInt(greetings.length)];
        } else if (message.equals("082")) {
            String[] greetings = {
                "新規さんよろしくです！", "新規さんよろしくです^^", "新規さんよろしくです～",
                "新規さん初めまして！", "新規さん初めまして", "新規さん初めまして～",
                "お初さんこんにちは！", "お初さんこんにちは^^", "お初さんこんにちは～",
                "お初さんよろしくお願いします", "お初さんよろしくお願いします！", "お初さんよろしくお願いします～",
                "初見さんいらっしゃい！", "初見さんいらっしゃい", "初見さんいらっしゃい～",
                "よろしくね！", "よろしくね^^", "よろしくね～",
                "よろしくお願いします", "よろしくお願いします", "よろしくお願いします～",
                "はじめまして！", "はじめまして^^", "はじめまして～",
                "これからよろしくお願いします", "これからよろしくお願いします！",
            };
            return greetings[random.nextInt(greetings.length)];
        }
        
        return null;
    }

    public String translate(String message) {
        // NGワードをマスクする（ローマ字）
        message = maskNGWord(message, ngwords);

        // 特定の数字コードをランダムな挨拶に変換
        String greetingTranslation = translateGreetingCode(message);
        if (greetingTranslation != null) {
            return ChatColor.WHITE + greetingTranslation + ChatColor.GRAY + " (" + message + ")";
        }

        if (Japanizer.isNeedToJapanize(message)) {
            // Japanizeで、日本語化する
            String japanize = Japanizer.japanize(message);
            if ( japanize.length() > 0 ) {
                // NGワードをマスクする（日本語）
                japanize = maskNGWord(japanize, ngwords);
            }

            String result = ChatColor.WHITE + japanize
                    + ChatColor.GRAY + " (" + message + ")";

            return result;
        } else {
            // 日本語化しない場合は、そのまま返す
            return ChatColor.WHITE + message;
        }

    }

    private String buildChatDisplayName(Player player) {
        String baseName = player.getDisplayName();
        long playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);

        if (playTicks <= SIX_HOURS_TICKS) {
            StringBuilder name = new StringBuilder(baseName);
            name.append(ChatColor.GREEN).append("*");
            if (playTicks <= TWO_HOURS_TICKS) {
                name.append(ChatColor.GREEN).append("新規さん");
            }
            name.append(ChatColor.RESET);
            return name.toString();
        }

        return baseName;
    }

    private String buildBeginnerMark(Player player) {
        String BeginnerMark = " ";
        long playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);

        if (playTicks <= SIX_HOURS_TICKS) {
            StringBuilder name = new StringBuilder(BeginnerMark);
            name.append(ChatColor.GREEN).append("*");
            if (playTicks <= TWO_HOURS_TICKS) {
                name.append(ChatColor.GREEN).append("新規さん");
            }
            name.append(ChatColor.RESET);
            return name.toString();
        }

        return BeginnerMark;
    }

    public String getPlayerGroup(UUID uuid) {
        return playerGroups.get(uuid);
    }

    private void loadDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            try {
            if (dataFile.createNewFile()) {
                getLogger().info("data.yml ファイルを新規作成しました。");
            }
            } catch (IOException e) {
            getLogger().severe("data.yml ファイルの作成に失敗しました: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveDataFile() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("データの保存に失敗しました: " + e.getMessage());
        }
    }

    private void loadGroups() {
        for (String key : dataConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String group = dataConfig.getString(key);
                playerGroups.put(uuid, group);
            } catch (IllegalArgumentException ignored) {}
        }
    }

}