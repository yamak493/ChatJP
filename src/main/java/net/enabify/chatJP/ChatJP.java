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
    private final Map<UUID, Integer> playerGroups = new HashMap<>();

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

                sender.sendMessage(ChatColor.GOLD+"[グループチャット] "+ChatColor.WHITE+"グループから退出しました。");

                return true;
            }


            try {
                int groupId = Integer.parseInt(args[0]);
                playerGroups.put(senderUUID, groupId);
                dataConfig.set(senderUUID.toString(), groupId);
                saveDataFile();
                sender.sendMessage(ChatColor.GOLD+"[グループチャット] "+ChatColor.WHITE+"グループ " + groupId + " に参加しました！");
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.GOLD+"[グループチャット] "+ChatColor.WHITE+"グループ番号は半角で入力してください。");
            }



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

        target.sendMessage(ChatColor.WHITE + "<" + sender.getName() + "> " + result);

        // コンソールに表示
        getLogger().info("[" + sender.getName() + " -> " + target.getName() + "] " + result);

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
        Integer senderGroup = getPlayerGroup(senderUUID);

        // 「!」でグループではなく、全体でチャット
        if ( message.startsWith("!") ) {

            // メッセージの頭の「!」を削除
            message = message.substring(1); // 先頭の1文字を削除
            // メッセージを日本語化
            String result = translate(message);
            event.setMessage(result);

            return;
        }


        if (senderGroup == null) {
            //グループには参加していなかった場合（通常チャット）

            // メッセージを日本語化
            String result = translate(message);
            event.setMessage(result);

        } else {
            // グループに参加していた場合（グループチャット）
            event.setCancelled(true); // 通常のチャットはキャンセル

            // メッセージを日本語化
            String result = translate(message);

            String msg = ChatColor.GOLD + "[グループ" + senderGroup + "] "+
                    ChatColor.WHITE + "<" + event.getPlayer().getName() + "> " + result;

            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                Integer targetGroup = getPlayerGroup(onlinePlayer.getUniqueId());
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

    public String translate(String message) {
        // NGワードをマスクする（ローマ字）
        message = maskNGWord(message, ngwords);

        // Japanizeで、日本語化する
        String japanize = Japanizer.japanize(message);
        if ( japanize.length() > 0 ) {
            // NGワードをマスクする（日本語）
            japanize = maskNGWord(japanize, ngwords);
        }

        String result =   ChatColor.WHITE + message
                + ChatColor.GOLD + " (" + japanize + ")";

        return result;
    }

    public Integer getPlayerGroup(UUID uuid) {
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
                int group = dataConfig.getInt(key);
                playerGroups.put(uuid, group);
            } catch (IllegalArgumentException ignored) {}
        }
    }

}