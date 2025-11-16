package net.enabify.chatJP;

import io.ably.lib.realtime.AblyRealtime;
import io.ably.lib.realtime.Channel;
import io.ably.lib.types.AblyException;
import io.ably.lib.types.ClientOptions;
import io.ably.lib.types.Message;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

public class AblyManager {
    private final ChatJP plugin;
    private final Logger logger;
    private AblyRealtime ably;
    private final String apiKey;
    private final Set<String> subscribedChannels = new HashSet<>();

    public AblyManager(ChatJP plugin, String apiKey) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.apiKey = apiKey;
    }

    /**
     * Ablyに接続
     */
    public void connect() {
        // APIキーが設定されていないチェック
        if (apiKey == null || apiKey.isEmpty()) {
            logger.severe("Ably API キーが設定されていません。接続を中止します。");
            return;
        }
        
        // バックグラウンドスレッドで接続を実行
        Thread connectionThread = new Thread(() -> {
            try {
                ClientOptions options = new ClientOptions();
                options.key = apiKey;
                ably = new AblyRealtime(options);
                
                logger.info("Ablyへの接続を開始しました");
                
                // グローバルチャンネルを購読
                subscribeToChannel("global");
                
            } catch (AblyException e) {
                logger.severe("Ablyの接続に失敗しました: " + e.getMessage());
                e.printStackTrace();
            }
        });
        connectionThread.setDaemon(true);
        connectionThread.start();
    }

    /**
     * Ablyから切断
     */
    public void disconnect() {
        if (ably != null) {
            try {
                // 接続状態を確認してから切断
                if (ably.connection != null) {
                    ably.connection.close();
                }
                ably.close();
                logger.info("Ablyから切断しました");
            } catch (Exception e) {
                logger.warning("Ablyの切断中にエラーが発生しました: " + e.getMessage());
                e.printStackTrace();
            }
            
            // スレッドが完全に終了するのを待つ
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.warning("Ablyシャットダウン待機中に割り込まれました");
            }
            
            ably = null;
            subscribedChannels.clear();
        }
    }

    /**
     * 指定されたチャンネルを購読
     * @param channelId チャンネルID
     */
    public void subscribeToChannel(String channelId) {
        if (ably == null) {
            logger.warning("Ablyが初期化されていません");
            return;
        }

        // チャンネルIDにnamespaceを付与
        String namespacedChannelId = addNamespace(channelId);

        // 既に購読済みの場合はスキップ
        if (subscribedChannels.contains(namespacedChannelId)) {
            logger.fine("チャンネル「" + namespacedChannelId + "」は既に購読済みです");
            return;
        }

        try {
            Channel channel = ably.channels.get(namespacedChannelId);
            channel.subscribe(message -> {
                // メッセージ処理をバックグラウンドスレッドで実行
                try {
                    handleIncomingMessage(channelId, message);
                } catch (Exception e) {
                    logger.warning("メッセージハンドリング中にエラーが発生しました: " + e.getMessage());
                    e.printStackTrace();
                }
            });
            subscribedChannels.add(namespacedChannelId);
            logger.info("チャンネル「" + namespacedChannelId + "」を購読しました");
        } catch (AblyException e) {
            logger.severe("チャンネル「" + namespacedChannelId + "」の購読に失敗しました: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * グループチャンネルを購読（必要に応じて）
     * @param groupId グループID
     */
    public void subscribeToGroup(String groupId) {
        if (groupId != null && !groupId.isEmpty() && !groupId.equals("global")) {
            subscribeToChannel(groupId);
        }
    }

    /**
     * 指定されたチャンネルの購読を中止
     * @param channelId チャンネルID
     */
    public void unsubscribeFromChannel(String channelId) {
        if (ably == null) {
            logger.warning("Ablyが初期化されていません");
            return;
        }

        // チャンネルIDにnamespaceを付与
        String namespacedChannelId = addNamespace(channelId);
        
        Channel channel = ably.channels.get(namespacedChannelId);
        channel.unsubscribe();
        subscribedChannels.remove(namespacedChannelId);
        logger.info("チャンネル「" + namespacedChannelId + "」の購読を中止しました");
    }

    /**
     * Ablyからのメッセージを受信したときの処理
     * @param channelId チャンネルID
     * @param message 受信したメッセージ
     */
    private void handleIncomingMessage(String channelId, Message message) {
        try {
            // メッセージデータを取得
            Object data = message.data;
            
            // データがJsonObjectかStringかチェック
            JSONObject json;
            if (data instanceof String) {
                json = new JSONObject((String) data);
            } else {
                // JsonObjectやその他の型の場合、toString()で文字列に変換
                json = new JSONObject(data.toString());
            }
            
            String playerName = json.getString("playerName");
            String text = json.getString("message");
            
            // このサーバーのプレイヤーからのメッセージかチェック
            // 同じメッセージが重複しないように、外部からのメッセージのみ表示
            String serverId = json.optString("senderId", "");
            String currentServerId = getServerId();
            
            if (serverId.equals(currentServerId)) {
                // 自分のサーバーからのメッセージは無視
                //logger.info("自サーバーからのメッセージを無視しました: " + text);
                return;
            }
            
            // チャンネルに応じてフォーマットを変更
            String formattedMessage;
            if (channelId.equals("global")) {
                formattedMessage = ChatColor.WHITE + "{" + playerName + "} " + text;
            } else {
                formattedMessage = ChatColor.GOLD + "[グループ | " + channelId + "] " +
                        ChatColor.WHITE + "{" + playerName + "} " + text;
            }
            
            // メインスレッドで実行するようスケジュール
            try {
                Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                    // 該当するプレイヤーにメッセージを送信
                    if (channelId.equals("global")) {
                        // 全体チャット
                        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                            onlinePlayer.sendMessage(formattedMessage);
                        }
                    } else {
                        // グループチャット
                        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                            String playerGroup = plugin.getPlayerGroup(onlinePlayer.getUniqueId());
                            if (channelId.equals(playerGroup)) {
                                onlinePlayer.sendMessage(formattedMessage);
                            }
                        }
                    }
                    
                    // コンソールに表示
                    logger.info(formattedMessage);
                });
            } catch (UnsupportedOperationException e) {
                // Foliaなどで非同期コンテキストからのスケジューリングがサポートされていない場合
                //logger.warning("Foliaサーバーで実行中です。メッセージの同期遅延が発生する可能性があります。");
                // 該当するプレイヤーにメッセージを送信
                if (channelId.equals("global")) {
                    // 全体チャット
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        onlinePlayer.sendMessage(formattedMessage);
                    }
                } else {
                    // グループチャット
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        String playerGroup = plugin.getPlayerGroup(onlinePlayer.getUniqueId());
                        if (channelId.equals(playerGroup)) {
                            onlinePlayer.sendMessage(formattedMessage);
                        }
                    }
                }
                
                // コンソールに表示
                logger.info(formattedMessage);
            }
            
        } catch (Exception e) {
            logger.warning("Ablyからのメッセージ処理に失敗しました: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Ablyにメッセージを送信
     * @param channelId チャンネルID
     * @param playerName プレイヤー名
     * @param message メッセージ
     */
    public void sendMessage(String channelId, String playerName, String message) {
        if (ably == null) {
            logger.warning("Ablyが初期化されていません");
            return;
        }

        // チャンネルIDにnamespaceを付与
        String namespacedChannelId = addNamespace(channelId);

        try {
            Channel channel = ably.channels.get(namespacedChannelId);
            
            // JSON形式でメッセージを作成
            JSONObject json = new JSONObject();
            json.put("playerName", playerName);
            json.put("message", message);
            json.put("senderId", getServerId());
            json.put("timestamp", System.currentTimeMillis());
            
            channel.publish("chat", json.toString());

            //logger.fine("Ablyにメッセージを送信しました: " + json.toString());
            //logger.info("Ablyにメッセージを送信しました: チャンネル " + namespacedChannelId + ", プレイヤー " + playerName + ", メッセージ " + message);
            
        } catch (AblyException e) {
            logger.severe("Ablyへのメッセージ送信に失敗しました: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * サーバーIDを取得（同じメッセージの重複を防ぐため）
     * @return サーバーID
     */
    private String getServerId() {
        // サーバーのポート番号を使用してIDを生成
        // より良い方法としては、設定ファイルで固有のIDを設定することも可能
        return "minecraft-" + Bukkit.getPort();
    }

    /**
     * Ablyが接続されているかチェック
     * @return 接続されている場合true
     */
    public boolean isConnected() {
        return ably != null && ably.connection.state == io.ably.lib.realtime.ConnectionState.connected;
    }

    /**
     * チャンネルIDにMinecraft namespaceを付与
     * @param channelId チャンネルID
     * @return namespaceが付与されたチャンネルID
     */
    private String addNamespace(String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return channelId;
        }
        if (channelId.contains(":")) {
            // すでにnamespaceが含まれている場合はそのまま返す
            return channelId;
        }
        return "minecraft:" + channelId;
    }
}
