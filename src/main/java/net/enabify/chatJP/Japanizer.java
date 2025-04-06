/*
 * @author     ucchy
 * @license    LGPLv3
 * @copyright  Copyright ucchy 2014
 */
package net.enabify.chatJP;

import java.util.HashMap;

/**
 * ローマ字表記を漢字変換して返すユーティリティ
 * @author ucchy
 */
public class Japanizer {

    private static final String REGEX_URL = "https?://[\\w/:%#\\$&\\?\\(\\)~\\.=\\+\\-]+";

    /**
     * メッセージの日本語化をする
     * @param org
     * @return
     */
    public static String japanize(String org) {

        JapanizeType type = JapanizeType.GOOGLE_IME;

        // URL削除
        String deletedURL = org.replaceAll(REGEX_URL, " ");

        // キーワードをロック
        HashMap<String, String> keywordMap = new HashMap<String, String>();
        String keywordLocked = deletedURL;

        // カナ変換
        String japanized = KanaConverter.conv(keywordLocked);

        // IME変換
        if ( type == JapanizeType.GOOGLE_IME ) {
            japanized = IMEConverter.convByGoogleIME(japanized);
        }

        // キーワードのアンロック
        for ( String key : keywordMap.keySet() ) {
            japanized = japanized.replace(key, keywordMap.get(key));
        }

        // 返す
        return japanized.trim();
    }

    /**
     * 日本語化が必要かどうかを判定する
     * @param org
     * @return
     */
    public static boolean isNeedToJapanize(String org) {
        return ( org.getBytes().length == org.length()
                && !org.matches("[ \\uFF61-\\uFF9F]+") );
    }

    /**
     * 数値を、全角文字の文字列に変換して返す
     * @param digit
     * @return
     */
    private static String makeMultibytesDigit(int digit) {

        String half = Integer.toString(digit);
        StringBuilder result = new StringBuilder();
        for ( int index=0; index < half.length(); index++ ) {
            switch ( half.charAt(index) ) {
            case '0' : result.append("０"); break;
            case '1' : result.append("１"); break;
            case '2' : result.append("２"); break;
            case '3' : result.append("３"); break;
            case '4' : result.append("４"); break;
            case '5' : result.append("５"); break;
            case '6' : result.append("６"); break;
            case '7' : result.append("７"); break;
            case '8' : result.append("８"); break;
            case '9' : result.append("９"); break;
            }
        }
        return result.toString();
    }
}
