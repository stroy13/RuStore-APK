package ru.rustoredl;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Шифрование истории скачиваний (AES-256-GCM).
 * Ключ генерируется в Android Keystore и не покидает защищённое хранилище:
 * из файлов нельзя восстановить содержимое без устройства.
 */
public final class CryptoUtil {

    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "rustoredl_hist_v1";
    private static final String PREFIX = "enc1:";

    private CryptoUtil() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        SecretKey existing = (SecretKey) ks.getKey(ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** Шифрует строку; при любой ошибке возвращает исходный текст (совместимость). */
    public static String protect(String plain) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return plain;
        }
    }

    /** Расшифровывает строку, записанную protect(); обычный текст проходит насквозь. */
    public static String unprotect(String s) {
        if (s == null || !s.startsWith(PREFIX)) return s;
        try {
            byte[] all = Base64.decode(s.substring(PREFIX.length()), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), "UTF-8");
        } catch (Exception e) {
            return "[]"; // не расшифровывается (другое устройство/ключ) — начинаем с чистой истории
        }
    }
}
