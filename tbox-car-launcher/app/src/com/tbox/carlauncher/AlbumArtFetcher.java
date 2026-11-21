package com.tbox.carlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous album cover fetcher with LruCache + disk cache for Bluetooth AVRCP tracks
 * (iPhone / sdsdk816 AVRCP only sends ID3 text metadata without BIP cover images).
 * Queries QQ Music (fast CN CDN + huge Chinese catalog) first, falling back to iTunes Search API.
 */
public final class AlbumArtFetcher {
    private static final String TAG = "TboxAlbumArt";
    private static final int CONNECT_TIMEOUT_MS = 3500;
    private static final int READ_TIMEOUT_MS = 4500;
    private static final long FAIL_TTL_MS = 10 * 60 * 1000L;

    public interface Callback {
        void onArtLoaded(String trackKey, Bitmap bitmap);
    }

    private final File cacheDir;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> memCache = new LruCache<String, Bitmap>(36);
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<String>());
    private final Map<String, Long> failedAt = Collections.synchronizedMap(new HashMap<String, Long>());

    public AlbumArtFetcher(Context ctx) {
        this.cacheDir = new File(ctx.getApplicationContext().getCacheDir(), "album_art");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    public static String makeKey(String title, String artist, String album) {
        String t = title == null ? "" : title.trim();
        String a = artist == null ? "" : artist.trim();
        String al = album == null ? "" : album.trim();
        return t + "|" + a + "|" + al;
    }

    public Bitmap getFromMemory(String key) {
        if (TextUtils.isEmpty(key)) return null;
        return memCache.get(key);
    }

    public void fetchAsync(final String title, final String artist, final String album, final Callback cb) {
        if (TextUtils.isEmpty(title)) return;
        final String key = makeKey(title, artist, album);
        Bitmap cached = memCache.get(key);
        if (cached != null) {
            if (cb != null) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onArtLoaded(key, cached);
                    }
                });
            }
            return;
        }
        Long failedTs = failedAt.get(key);
        if (failedTs != null && (System.currentTimeMillis() - failedTs) < FAIL_TTL_MS) {
            return;
        }
        if (!inFlight.add(key)) {
            return;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap result = null;
                try {
                    result = loadFromDisk(key);
                    if (result == null) {
                        result = fetchOnline(title, artist, album);
                        if (result != null) {
                            saveToDisk(key, result);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "fetchAsync error for " + key, t);
                } finally {
                    inFlight.remove(key);
                }

                if (result != null) {
                    memCache.put(key, result);
                    failedAt.remove(key);
                    final Bitmap finalBmp = result;
                    if (cb != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onArtLoaded(key, finalBmp);
                            }
                        });
                    }
                } else {
                    failedAt.put(key, System.currentTimeMillis());
                }
            }
        });
    }

    private Bitmap fetchOnline(String title, String artist, String album) {
        String cleanTitle = stripBrackets(title);
        String cleanArtist = isPlaceholderArtist(artist) ? "" : artist.trim();
        String query = TextUtils.isEmpty(cleanArtist) ? cleanTitle : (cleanTitle + " " + cleanArtist);

        // 1. Try QQ Music (fast Tencent CDN, best Chinese + Pop hit rate)
        Bitmap bmp = fetchFromQqMusic(query, cleanTitle, cleanArtist, album);
        if (bmp != null) return bmp;

        // 2. Fallback to iTunes Search API (excellent global / HK-TW / JP-EN hit rate)
        bmp = fetchFromItunes(query, cleanTitle, cleanArtist, album);
        if (bmp != null) return bmp;

        // 3. If artist was non-empty and both failed, retry with title alone
        if (!TextUtils.isEmpty(cleanArtist) && !cleanTitle.equals(query)) {
            bmp = fetchFromQqMusic(cleanTitle, cleanTitle, cleanArtist, album);
            if (bmp != null) return bmp;
            bmp = fetchFromItunes(cleanTitle, cleanTitle, cleanArtist, album);
            if (bmp != null) return bmp;
        }
        return null;
    }

    private Bitmap fetchFromQqMusic(String query, String targetTitle, String targetArtist, String albumHint) {
        try {
            String url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=5&w="
                + URLEncoder.encode(query, "UTF-8") + "&format=json";
            String body = httpGetString(url, "https://y.qq.com/");
            if (TextUtils.isEmpty(body)) return null;

            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            JSONObject song = data != null ? data.optJSONObject("song") : null;
            JSONArray list = song != null ? song.optJSONArray("list") : null;
            if (list == null || list.length() == 0) return null;

            String bestMid = "";
            int bestScore = 0;
            String albumNorm = normalize(albumHint);
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String mid = item.optString("albummid", "").trim();
                if (mid.isEmpty() || "00000000000000".equals(mid)) continue;
                String itemTitle = item.optString("songname", "");
                String itemAlbum = item.optString("albumname", "");
                String itemSinger = extractQqSingers(item.optJSONArray("singer"));
                int score = matchScore(targetTitle, targetArtist, albumNorm, itemTitle, itemSinger, itemAlbum);
                if (score > bestScore) {
                    bestScore = score;
                    bestMid = mid;
                }
            }
            if (bestMid.isEmpty() || bestScore < 2) return null;

            String imgUrl = "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + bestMid + ".jpg";
            Bitmap bmp = httpGetBitmap(imgUrl, "https://y.qq.com/");
            if (bmp != null) {
                Log.i(TAG, "QQ Music cover loaded for '" + query + "' score=" + bestScore
                    + " (" + bmp.getWidth() + "x" + bmp.getHeight() + ")");
            }
            return bmp;
        } catch (Throwable t) {
            Log.d(TAG, "QQ Music lookup failed for '" + query + "': " + t.getMessage());
            return null;
        }
    }

    private Bitmap fetchFromItunes(String query, String targetTitle, String targetArtist, String albumHint) {
        try {
            String url = "https://itunes.apple.com/search?term="
                + URLEncoder.encode(query, "UTF-8") + "&media=music&entity=song&limit=5";
            String body = httpGetString(url, null);
            if (TextUtils.isEmpty(body)) return null;

            JSONObject root = new JSONObject(body);
            JSONArray results = root.optJSONArray("results");
            if (results == null || results.length() == 0) return null;

            String bestArtUrl = "";
            int bestScore = 0;
            String albumNorm = normalize(albumHint);
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                String artUrl = item.optString("artworkUrl100", "").trim();
                if (artUrl.isEmpty()) continue;
                String itemTitle = item.optString("trackName", "");
                String itemArtist = item.optString("artistName", "");
                String itemAlbum = item.optString("collectionName", "");
                int score = matchScore(targetTitle, targetArtist, albumNorm, itemTitle, itemArtist, itemAlbum);
                if (score > bestScore) {
                    bestScore = score;
                    bestArtUrl = artUrl;
                }
            }
            if (bestArtUrl.isEmpty() || bestScore < 2) return null;

            bestArtUrl = bestArtUrl.replace("100x100bb", "300x300bb");
            Bitmap bmp = httpGetBitmap(bestArtUrl, null);
            if (bmp != null) {
                Log.i(TAG, "iTunes cover loaded for '" + query + "' score=" + bestScore
                    + " (" + bmp.getWidth() + "x" + bmp.getHeight() + ")");
            }
            return bmp;
        } catch (Throwable t) {
            Log.d(TAG, "iTunes lookup failed for '" + query + "': " + t.getMessage());
            return null;
        }
    }

    private static String extractQqSingers(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(o.optString("name", ""));
            }
        }
        return sb.toString();
    }

    /**
     * Prevents false-positive covers for non-music videos/podcasts (e.g. Bilibili).
     * Requires at least title similarity (>=2) or exact album match (>=2).
     */
    private static int matchScore(String tTitle, String tArtist, String tAlbumNorm,
                                  String rTitle, String rArtist, String rAlbum) {
        String nt = normalize(tTitle);
        String rt = normalize(rTitle);
        int score = 0;
        if (!nt.isEmpty() && !rt.isEmpty()) {
            if (nt.equals(rt)) score += 4;
            else if (rt.contains(nt) || nt.contains(rt)) score += 3;
            else if (charOverlapRatio(nt, rt) >= 0.5f) score += 2;
        }
        String na = normalize(tArtist);
        String ra = normalize(rArtist);
        if (!na.isEmpty() && !ra.isEmpty()) {
            if (ra.contains(na) || na.contains(ra)) score += 2;
        }
        String ral = normalize(rAlbum);
        if (!tAlbumNorm.isEmpty() && !ral.isEmpty()) {
            if (ral.contains(tAlbumNorm) || tAlbumNorm.contains(ral)) score += 2;
        }
        return score;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[\\s\\p{Punct}·，。！？、（）【】《》—\\-]+", "");
    }

    private static float charOverlapRatio(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0f;
        int hits = 0;
        for (int i = 0; i < a.length(); i++) {
            if (b.indexOf(a.charAt(i)) >= 0) hits++;
        }
        return (float) hits / (float) Math.max(a.length(), b.length());
    }

    private String httpGetString(String urlStr, String referer) throws Exception {
        byte[] bytes = httpGetBytes(urlStr, referer, 256 * 1024);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    private Bitmap httpGetBitmap(String urlStr, String referer) throws Exception {
        byte[] bytes = httpGetBytes(urlStr, referer, 512 * 1024);
        if (bytes == null || bytes.length < 128) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    private byte[] httpGetBytes(String urlStr, String referer, int maxBytes) throws Exception {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36");
            if (referer != null) conn.setRequestProperty("Referer", referer);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) return null;
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) {}
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private File cacheFile(String key) {
        return new File(cacheDir, md5(key) + ".jpg");
    }

    private Bitmap loadFromDisk(String key) {
        File f = cacheFile(key);
        if (!f.exists() || f.length() < 128) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void saveToDisk(String key, Bitmap bmp) {
        File f = cacheFile(key);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) try { fos.close(); } catch (Throwable ignored) {}
        }
    }

    private static String stripBrackets(String s) {
        if (s == null) return "";
        String cleaned = s.replaceAll("\\s*[\\(（\\[【].*?[\\)）\\]】]", "").trim();
        return cleaned.isEmpty() ? s.trim() : cleaned;
    }

    private static boolean isPlaceholderArtist(String artist) {
        if (TextUtils.isEmpty(artist)) return true;
        String a = artist.trim();
        return "手机蓝牙音频".equals(a) || "蓝牙音乐".equals(a) || "未知".equals(a);
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(s.hashCode());
        }
    }
}
