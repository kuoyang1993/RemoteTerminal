package com.remoteterminal.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * 密码本地加密工具 — AES-256/GCM，密钥源自固定种子 + PBKDF2
 */
public final class EncryptionUtil {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int KEY_LENGTH = 256;
    private static final byte[] SALT = {
            0x5f, 0x18, (byte)0xa3, 0x7c, 0x2b, 0x4e, 0x1d, (byte)0x96
    };
    private static final char[] SEED = "RemoteTerminal@2024#LocalStore".toCharArray();

    private static SecretKey deriveKey() throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(SEED, SALT, PBKDF2_ITERATIONS, KEY_LENGTH);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    /** 加密明文密码，返回 Base64 密文 */
    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return "";
        try {
            SecretKey key = deriveKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + encrypted.length);
            buf.put(iv);
            buf.put(encrypted);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            // 加密失败时回退到 Base64 编码（至少非明文）
            return "_b64_" + Base64.getEncoder().encodeToString(plainText.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 解密密文，返回明文密码 */
    public static String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) return "";
        // 兼容旧版 Base64 回退
        if (cipherText.startsWith("_b64_")) {
            return new String(Base64.getDecoder().decode(cipherText.substring(5)), StandardCharsets.UTF_8);
        }
        try {
            SecretKey key = deriveKey();
            byte[] data = Base64.getDecoder().decode(cipherText);
            ByteBuffer buf = ByteBuffer.wrap(data);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] encrypted = new byte[buf.remaining()];
            buf.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return ""; // 解密失败返回空
        }
    }
}
