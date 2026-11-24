package com.teaplayer.teahome.config;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM envelope for secret configuration values.
 *
 * <p>Keyring format: {@code k2:<base64 32B>,k1:<base64 32B>}; the first entry is used for
 * new writes, older entries remain valid for reads. AAD binds a ciphertext to its key.</p>
 */
@Component
public class SecretBox {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
    private static final String AAD_PREFIX = "teaplayer_config_value|";
    private static final int NONCE_BYTES = 12;

    private final Map<String, SecretKey> keys = new LinkedHashMap<>();
    private final String currentKeyId;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(Environment environment) {
        String raw = environment.getProperty("TEAHOME_CONFIG_KEYS");
        String current = null;
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                String[] pieces = part.trim().split(":", 2);
                if (pieces.length != 2) {
                    continue;
                }
                byte[] material = Base64.getDecoder().decode(pieces[1].trim());
                if (material.length != 32) {
                    throw new IllegalStateException("config key " + pieces[0] + " must be 32 bytes");
                }
                keys.put(pieces[0].trim(), new SecretKeySpec(material, "AES"));
                if (current == null) {
                    current = pieces[0].trim();
                }
            }
        }
        this.currentKeyId = current;
        if (keys.isEmpty()) {
            log.warn("TEAHOME_CONFIG_KEYS 未配置，secret 类型配置不可写");
        } else {
            log.info("配置密钥环已加载：{} 个密钥，当前 {}", keys.size(), current);
        }
    }

    public boolean configured() {
        return currentKeyId != null;
    }

    public record Sealed(byte[] cipher, byte[] nonce, String keyId, String hint) {
    }

    public Sealed seal(String key, String plaintext) {
        if (!configured()) {
            throw new IllegalStateException("secret keyring is not configured");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(currentKeyId), new GCMParameterSpec(128, nonce));
            cipher.updateAAD((AAD_PREFIX + key).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Sealed(encrypted, nonce, currentKeyId, mask(plaintext));
        } catch (Exception e) {
            throw new IllegalStateException("cannot seal config value " + key, e);
        }
    }

    public String open(String key, String keyId, byte[] nonce, byte[] ciphertext) {
        SecretKey secretKey = keys.get(keyId);
        if (secretKey == null) {
            throw new IllegalStateException("unknown config key id " + keyId);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, nonce));
            cipher.updateAAD((AAD_PREFIX + key).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot open config value " + key, e);
        }
    }

    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return "******";
    }
}
