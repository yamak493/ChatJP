package net.enabify.chatJP;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

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
    private AblyManager ablyManager;

    // Ably APIキー（設定ファイルから読み込み）
    private String ablyApiKey;

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
        loadConfigFile();
        loadDataFile();
        loadGroups();

        // APIキーが設定されている場合のみAblyマネージャーを初期化
        if (ablyApiKey != null && !ablyApiKey.isEmpty()) {
            ablyManager = new AblyManager(this, ablyApiKey);
            ablyManager.connect();

            // 既存のグループチャンネルも購読
            for (String groupId : new HashSet<>(playerGroups.values())) {
                if (groupId != null && !groupId.isEmpty() && !groupId.equals("global")) {
                    ablyManager.subscribeToGroup(groupId);
                }
            }
        } else {
            getLogger().warning("Ably API キーが設定されていません。Ablyは無効になります。");
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ChatJP plugin enabled!");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        saveDataFile();

        // Ablyから切断
        if (ablyManager != null) {
            try {
                ablyManager.disconnect();
            } catch (Exception e) {
                getLogger().warning("Ably切断中にエラーが発生しました: " + e.getMessage());
            }
        }

        getLogger().info("ChatJP plugin disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // プレイヤーのみが利用可能
        if (!(sender instanceof Player)) return false;

        UUID senderUUID = ((Player) sender).getUniqueId();

        if (command.getName().equalsIgnoreCase("group")) {
            if (args.length < 1) {
                UUID playerUUID = senderUUID;
                String playerGroup = getPlayerGroup(playerUUID);

                playerGroups.remove(senderUUID);
                dataConfig.set(senderUUID.toString(), null);
                saveDataFile();

                sender.sendMessage(ChatColor.GOLD+"[グループチャット] "+ChatColor.WHITE+"グループから退出しました。");

                // グループに他のプレイヤーがいるかチェック
                if (playerGroup != null && !playerGroup.isEmpty() && !playerGroup.equals("global")) {
                    int playerCountInGroup = 0;
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        if (onlinePlayer.getUniqueId().equals(playerUUID)) {
                            continue; // 退出するプレイヤーは除外
                        }
                        String otherPlayerGroup = getPlayerGroup(onlinePlayer.getUniqueId());
                        if (playerGroup.equals(otherPlayerGroup)) {
                            playerCountInGroup++;
                        }
                    }
                    
                    // グループに誰もいなくなった場合、チャンネルの購読を中止
                    if (playerCountInGroup == 0) {
                        if (ablyManager != null) {
                            ablyManager.unsubscribeFromChannel(playerGroup);
                            getLogger().info("グループ「" + playerGroup + "」にプレイヤーがいなくなったため、チャンネルの購読を中止しました。");
                        }
                    }
                }

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
            
            // 新しいグループチャンネルを購読
            ablyManager.subscribeToGroup(groupId);
            
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

        target.sendMessage(ChatColor.WHITE + "[" + sender.getName() + " -> "+target.getName()+"] " + result);

        // コンソールに表示
        getLogger().info("[" + sender.getName() + " -> " + target.getName() + "] " + result);

        // 送信者にもログが残るようにする
        sender.sendMessage(ChatColor.WHITE + "[" + sender.getName() + " -> "+target.getName()+"] " + result);

        return true;
    }

    /**
     * プレイヤーがサーバーに参加したときに呼び出されるメソッド
     * @param event
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerUUID = event.getPlayer().getUniqueId();
        String playerGroup = getPlayerGroup(playerUUID);
        
        // プレイヤーがグループに参加している場合、そのグループチャンネルを購読
        if (playerGroup != null && !playerGroup.isEmpty() && !playerGroup.equals("global")) {
            if (ablyManager != null) {
                ablyManager.subscribeToGroup(playerGroup);
                getLogger().info("プレイヤー " + event.getPlayer().getName() + " がグループ「" + playerGroup + "」に参加しているため、チャンネルを購読しました。");
            }
        }
    }

    /**
     * プレイヤーがサーバーから退出したときに呼び出されるメソッド
     * @param event
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUUID = event.getPlayer().getUniqueId();
        String playerGroup = getPlayerGroup(playerUUID);
        
        // プレイヤーがグループに参加している場合、そのグループに他のプレイヤーがいるかチェック
        if (playerGroup != null && !playerGroup.isEmpty() && !playerGroup.equals("global")) {
            // グループに参加中の他のプレイヤー数をカウント
            int playerCountInGroup = 0;
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.getUniqueId().equals(playerUUID)) {
                    continue; // 退出するプレイヤーは除外
                }
                String otherPlayerGroup = getPlayerGroup(onlinePlayer.getUniqueId());
                if (playerGroup.equals(otherPlayerGroup)) {
                    playerCountInGroup++;
                }
            }
            
            // グループに誰もいなくなった場合、チャンネルの購読を中止
            if (playerCountInGroup == 0) {
                if (ablyManager != null) {
                    ablyManager.unsubscribeFromChannel(playerGroup);
                    getLogger().info("グループ「" + playerGroup + "」にプレイヤーがいなくなったため、チャンネルの購読を中止しました。");
                }
            }
        }
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
            event.setMessage(result);

            // Ablyに全体チャットとして送信
            if (ablyManager != null) {
                String plainResult = ChatColor.stripColor(result);
                ablyManager.sendMessage("global", event.getPlayer().getName(), plainResult);
            }

            return;
        }


        if (senderGroup == null) {
            //グループには参加していなかった場合（通常チャット）

            // メッセージを日本語化
            String result = translate(message);
            event.setMessage(result);

            // Ablyに全体チャットとして送信
            if (ablyManager != null) {
                String plainResult = ChatColor.stripColor(result);
                ablyManager.sendMessage("global", event.getPlayer().getName(), plainResult);
            }

        } else {
            // グループに参加していた場合（グループチャット）
            event.setCancelled(true); // 通常のチャットはキャンセル

            // メッセージを日本語化
            String result = translate(message);

            String msg = ChatColor.GOLD + "[グループ | " + senderGroup + "] "+
                    ChatColor.WHITE + "<" + event.getPlayer().getName() + "> " + result;

            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                String targetGroup = getPlayerGroup(onlinePlayer.getUniqueId());
                if (senderGroup.equals(targetGroup)) {
                    onlinePlayer.sendMessage(msg);
                }
            }

            // コンソールに表示
            getLogger().info(msg);

            // Ablyにグループチャットとして送信
            if (ablyManager != null) {
                String plainResult = ChatColor.stripColor(result);
                ablyManager.sendMessage(senderGroup, event.getPlayer().getName(), plainResult);

                //getLogger().info("Ablyにグループチャットメッセージを送信しました: グループ " + senderGroup + ", プレイヤー " + event.getPlayer().getName() + ", メッセージ " + plainResult);
            }
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
                "こんちゃ！", "こんちゃ～"
            };
            return greetings[random.nextInt(greetings.length)];
        } else if (message.equals("08")) {
            String[] greetings = {
                "おはよう", "おはよう！", "おはよう^^", "おはよう～", "おはようー",
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

            String result =   ChatColor.WHITE + message
                    + ChatColor.GOLD + " (" + japanize + ")";

            return result;
        } else {
            // 日本語化しない場合は、そのまま返す
            return ChatColor.WHITE + message;
        }

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

    private void loadConfigFile() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            configFile.getParentFile().mkdirs();
            try {
                if (configFile.createNewFile()) {
                    getLogger().info("config.yml ファイルを新規作成しました。");
                    
                    // デフォルト設定を作成
                    FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
                    config.set("ably.api-key", "YOUR_ABLY_API_KEY_HERE");
                    config.save(configFile);
                    
                    getLogger().warning("config.yml に Ably API キーを設定してください！");
                }
            } catch (IOException e) {
                getLogger().severe("config.yml ファイルの作成に失敗しました: " + e.getMessage());
            }
        }
        
        // 設定ファイルから APIキーを読み込み
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        ablyApiKey = config.getString("ably.api-key", "");
        
        if (ablyApiKey.isEmpty() || ablyApiKey.equals("YOUR_ABLY_API_KEY_HERE")) {
            ablyApiKey = null;
        }
    }

}